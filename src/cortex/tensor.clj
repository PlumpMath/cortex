(ns cortex.tensor
  "Tensor library used to implement the basic math abstraction in cortex.  This abstraction is
meant to provide a language in which to implement new things but that explicitly avoids access
to certain parts of the comput ecosystem that the engine driving the ecosystem is expected
to manage.  Clients should not, for instance, access the stream or the datatype directly.
Currently the dimensions of tensors (like the dimensions of the graph) are hardcoded to
[batch-size channels height width].

There is an implicit assumption throughout this file that implementations will loop through
smaller entities instead of throwing an exception if sizes don't match.  This allows for
instance an efficient accumulation of a batch of gradients into a single summed buffer.

It does mean, however, that certain conditions that would actually be error cases are
harder to detect because one has to check for remainders being zero (which potentially
could cause a divide by zero error) instead of just checking for equality.

Assignment has two forms
y = x
y[idx] = x[idx]

For binary operations there are four forms:

y = a*x op b*y
result = a*x op b*y.
y[idx] = a*x[idx] op b*y[idx]
result[idx] = a*x[idx] op b*y[idx]

Op may be: [:+ :* :/].

In the non-indexed cases the element counts of y or x may differ but they need to be commensurate meaning
that the smaller evenly divides the larger.
When writing to result it is important that result is as large as the largest.

For indexed cases we can't enforce really any constraints but if a location in result is written to more
than once then the outcome is not defined; this is considered a programmatic error *!!that cannot be
  detected at runtime!!*  Locations in Y may be written to more than once.

In general we want as much error checking and analysis done in this file as opposed to at the implementation
level (compute stream level) so that different implementations of this duplicate the least number of
possible operations and so their edge cases agree to the extent possible.


For indirect operations element count is num-indexes * num-columns.  After that they should obey the same rules
if the element counts of various things do not match meaning the smaller should evenly divide the larger and
if a separate result is provided it must be the size of the larger."
  (:require [cortex.compute.driver :as compute-drv]
            [think.datatype.core :as dtype]
            [clojure.core.matrix.protocols :as mp]
            [mikera.vectorz.matrix-api]
            [cortex.graph :as graph]
            [clojure.core.matrix :as m]
            [think.resource.core :as resource]
            [clojure.math.combinatorics :as combo]
            [cortex.tensor.index-system :as is]
            [cortex.tensor.math :as tm]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defmacro when-not-error
  [expr error-msg extra-data]
  `(when-not ~expr
     (throw (ex-info ~error-msg ~extra-data))))


;;Stream is dynamically bound at execution time presumably by an entity outside of the context
;;of this file.  Due to this clients of this file should not be manipulating stream.
(def ^:dynamic *stream*)
;;Similar to stream, the engine will set this variable and clients should not set
;;the variable themselves.
(def ^:dynamic *datatype* :double)

(defn- check-stream
  []
  (let [retval *stream*]
    (when-not-error retval "Tensor stream is nil" {})
    retval))

(defn- ensure-datatypes
  [datatype & args]
  (when-not-error (every? #(= datatype (dtype/get-datatype %)) args)
    "Not all arguments match required datatype"
    {:datatype datatype
     :argument-datatypes (map dtype/get-datatype args)}))


(defn- ensure-same-driver
  "Given a set of tensors, ensure they share the same driver."
  [& args]
  (let [driver (:driver (first args))
        wrong-driver (->> (rest args)
                          (remove #(identical? driver (get % :driver)))
                          seq)]
    (when-not-error (nil? wrong-driver)
      "Tensor arguments must have same driver."
      {})))


(defn same-device?
  [& args]
  (apply ensure-same-driver args))


(defn- ensure-same-device
  "Given a set of tensors, ensure they share the same device.  Only assignment of identical
types is guaranteed to work across devices."
  [& args]
  (apply ensure-same-driver args))


(defn default-dimension-order
  []
  [:batch-size :channels :height :width])


(defn get-dimension-order
  [dims]
  (get dims :order (default-dimension-order)))


(defn create-dimensions
  "Dimensions are defined the same as the graph dimensions with the exception of the inclusion
  of batch size to the map as the slowest-changing dimension."
  [& {:keys [width height channels batch-size]
      :or {width 1 height 1 channels 1 batch-size 1} :as args}]
  {:shape [batch-size channels height width]})


(defn dimensions->map
  "Convert dimensions into a map containing {batch-size channels height width}"
  [{:keys [shape order]
    :or {order (default-dimension-order)}}]
  (let [[batch-size channels height width] shape]
    {:batch-size batch-size
     :channels channels
     :height height
     :width width
     :order order}))


(defn map->dimensions
  [{:keys [batch-size channels height width order]
    :or {batch-size 1
         channels 1
         height 1
         width 1
         order (default-dimension-order)}}]
  {:shape [batch-size channels height width]
   :order order})


(defn core-mat-shape->dimensions
  "Given a core-matrix shape produce a dimension map."
  ([shape ^long batch-size]
   ;;Divide the highest dimension of shape by batch size.
   (case (count shape)
     1 (create-dimensions :batch-size batch-size
                          :width (quot ^long (first shape)
                                       batch-size))
     2 (create-dimensions :batch-size batch-size
                          :height (quot ^long (first shape)
                                        batch-size)
                          :width (second shape))
     3 (create-dimensions :batch-size batch-size
                          :channels (quot ^long (first shape)
                                          batch-size)
                          :height (second shape)
                          :width (nth shape 2))
     (throw (ex-info "Unexpected shape"
                     {:shape shape
                      :batch-size batch-size}))))
  ([shape]
   (core-mat-shape->dimensions shape 1)))


(defn dimension-ecount
  "Return the element count indicated by the dimension map"
  ^long [{:keys [shape]}]
  (long (apply * shape)))


(defn dimensions->2d-shape
  "Given dimensions, return new dimensions with the lowest (fastest-changing) dimension
  unchanged and the rest of the dimensions multiplied into the higher dimension."
  [{:keys [shape]}]
  (when-not-error (seq shape)
    "Invalid shape in dimension map"
    {:shape shape})
  (if (= 1 (count shape))
    [1 (first shape)]
    [(apply * (drop-last shape)) (last shape)]))


(defn dimensions->batch-shape
  "Given dimensions, return new dimensions with the lowest (fastest-changing) dimension
  unchanged and the rest of the dimensions multiplied into the higher dimension."
  [{:keys [shape]}]
  (when-not-error (seq shape)
    "Invalid shape in dimension map"
    {:shape shape})
  (if (= 1 (count shape))
    [1 (first shape)]
    [(first shape) (apply * (drop 1 shape))]))


(defn dimensions->shape
  [{:keys [shape]}]
  shape)

(defn dimensions->most-rapidly-changing
  "Get the size of the most rapidly changing dimension"
  ^long [{:keys [shape]}]
  (last shape))

(defn dimensions->least-rapidly-changing
  "Get the size of the least rapidly changing dimension"
  ^long [{:keys [shape]}]
  (first shape))

(defn- ensure-elementwise-compatible
  "Ensure these two tensors are compatible for an elementwise operation
that rerequires the items to have the same element count."
  [lhs rhs]
  (when-not-error (identical? (compute-drv/get-driver lhs)
                              (compute-drv/get-driver rhs))
    "Tensor drivers do not match"
    {:lhs lhs
     :rhs rhs})
  (when-not-error (= (dtype/ecount lhs)
                     (dtype/ecount rhs))
    "Tensors must have same ecount for assignment."
    {:lhs-ecount (dtype/ecount lhs)
     :rhs-ecount (dtype/ecount rhs)})
  (when-not-error (= (dtype/get-datatype lhs)
                     (dtype/get-datatype rhs))
    "Tensor datatypes are mismatched"
    {:lhs-datatype (dtype/get-datatype lhs)
     :rhs-datatype (dtype/get-datatype rhs)}))


(declare strided? dense?)

(defn scalar?
  [item] (number? item))

(defn get-datatype
  [tensor]
  (dtype/get-datatype tensor))

(defn unsafe-get-driver
  "Return the driver for a given tensor.  This should not be necessary."
  [tensor]
  (compute-drv/get-driver tensor))

(defn shape
  [tensor]
  (mp/get-shape tensor))

(defn as-vector
  [tensor]
  (m/as-vector tensor))

(defn to-vector
  [tensor]
  (m/to-vector tensor))

(defn ecount
  ^long [tensor]
  (long (mp/element-count tensor)))

;;Tensors are a tuple of device (driver for now) dimensions and index system and buffer.
(defrecord Tensor [driver dimensions index-system buffer]
  dtype/PDatatype
  (get-datatype [tensor] (dtype/get-datatype (:buffer tensor)))
  compute-drv/PDriverProvider
  (get-driver [tensor] driver)
  mp/PElementCount
  (element-count [tensor]
    (dimension-ecount dimensions))
  mp/PDimensionInfo
  (dimensionality [m] (count (mp/get-shape m)))
  (get-shape [m] (dimensions->shape dimensions))
  (is-scalar? [m] false)
  (is-vector? [m] true)
  (dimension-count [m dimension-number]
    (let [shape (mp/get-shape m)]
      (if (<= (count shape) (long dimension-number))
        (get shape dimension-number)
        (throw (ex-info "Array does not have specific dimension"
                        {:dimension-number dimension-number
                         :shape shape}))))))


(defn- dimensions->column-stride
  ^long [dimensions index-system]
  (if-let [col-stride (get index-system :column-stride)]
    col-stride
    (last (get dimensions :shape))))


(defn tensor->index-system
  [^Tensor tensor]
  (.index-system tensor))


(defn- dimensions->num-columns
  ^long [dimensions index-system]
  (if-let [num-columns (get index-system :num-columns)]
    num-columns
    (last (get dimensions :shape))))


(defn- tensor->dimensions
  [^Tensor tensor]
  (.dimensions tensor))


(defn- tensor->column-stride
  ^long [^Tensor tensor]
  (dimensions->column-stride
   (tensor->dimensions tensor)
   (tensor->index-system tensor)))


(defn- tensor->num-columns
  ^long [^Tensor tensor]
  (dimensions->num-columns
   (tensor->dimensions tensor)
   (tensor->index-system tensor)))


(defn- tensor->driver
  [^Tensor tensor]
  (compute-drv/get-driver tensor))


(defn tensor->buffer
  [^Tensor tensor]
  (.buffer tensor))





(defn tensor->2d-shape
  [^Tensor tensor]
  (dimensions->2d-shape (tensor->dimensions tensor)))


(defn tensor->batch-shape
  [^Tensor tensor]
  (dimensions->batch-shape (tensor->dimensions tensor)))


(defn tensor->index-system
  [^Tensor tensor]
  (.index-system tensor))


(defn- ensure-assignment-matches
  [^Tensor dest ^Tensor src]
  ;;In order for marshalling or striding to work we need to ensure
  ;;we are on the same device.  device->device transfers only work with
  ;;a bulk dma transfer and that does not do any marshalling nor does it
  ;;do any indexing.
  (if-not (and (= (get-datatype dest) (get-datatype src))
               (dense? dest)
               (dense? src))
    (ensure-same-device dest src)
    (ensure-same-driver dest src)))

(defn- check-partial-alias
  [driver & args]
  (let [partially-overlapping-args (->> args
                                        (map #(tensor->buffer ^Tensor %))
                                        (combo/combinations args 2)
                                        (filter #(apply compute-drv/partially-alias? %))
                                        seq)]
    (when-not-error (nil? partially-overlapping-args)
      "Partially overlapping arguments detected."
      {})))


(defn tensor
  (^Tensor [driver dimensions index-system buffer]
   (let [buffer-ecount (ecount buffer)
         shape (dimensions->shape dimensions)
         column-stride (dimensions->column-stride dimensions index-system)
         required-buffer-ecount (long
                                 (apply * column-stride
                                        (drop-last shape)))]

     (when-not-error (<= required-buffer-ecount buffer-ecount)
       "Supplied buffer does not have enough capacity for declared dimensions"
       {:buffer-ecount buffer-ecount
        :dimensions dimensions
        :required-buffer-ecount required-buffer-ecount
        :column-stride column-stride
        :index-system index-system})
     (when-let [num-columns (get index-system :num-columns)]
      (when-not-error (<= (long num-columns)
                          (long column-stride))
        "Tensor buffer column-count is greater than supplied column stride"
        {:num-columns num-columns
         :column-stride column-stride})))
   (->Tensor driver dimensions index-system buffer))
  (^Tensor [driver dimensions buffer]
   (->Tensor driver dimensions
             (is/monotonically-increasing (dimension-ecount dimensions))
             buffer)))


(defn reinterpret-tensor
  "Create a new tensor with new dimensions.  This is like an in place reinterpretation of the
  data."
  ^Tensor [^Tensor tensor new-dimensions]
  (tensor (.driver tensor) new-dimensions
          (tensor->index-system tensor)
          (:buffer tensor)))


(defn as-column-vector
  [^Tensor tensor]
  (when-not-error (or (= 1 (tensor->num-columns tensor))
                      (dense? tensor))
    "Column vectors must either be dense or have num-columns = 1"
    {:dense? (dense? tensor)
     :num-columns (tensor->num-columns tensor)})
  (reinterpret-tensor tensor (create-dimensions :height (ecount tensor)
                                                :width 1)))

(defn as-row-vector
  [^Tensor tensor]
  (when-not-error (or (= 1 (tensor->num-columns tensor))
                      (dense? tensor))
    "Row vectors must either be dense or have num-columns = 1"
    {:dense? (dense? tensor)
     :num-columns (tensor->num-columns tensor)})
  (reinterpret-tensor tensor (create-dimensions :width (ecount tensor))))


(defn- datatype->keyword
  [item]
  (cond
    (instance? Tensor item) :tensor
    (number? item) :number))


(defn- element-counts-commensurate?
  [^long lhs-ecount ^long rhs-ecount]
  (or (= 0 rhs-ecount)
      (= 0 (rem lhs-ecount rhs-ecount))))

(defmulti typed-assign!
  "Multimethods for typed assignment."
  (fn
    [dest src]
    [(datatype->keyword dest)
     (datatype->keyword src)]))


(defn dense?
  [^Tensor tensor]
  (is/dense? (tensor->index-system tensor)))

(def strided? (complement dense?))


(defn tensor->batch-size
  ^long [^Tensor tensor] (dimensions->least-rapidly-changing (tensor->dimensions tensor)))


(defn tensor->channels
  ^long [^Tensor tensor]
  (get (dimensions->map (tensor->dimensions tensor)) :channels))


(defn tensor->height
  ^long [^Tensor tensor]
  (get (dimensions->map (tensor->dimensions tensor)) :height))


(defn tensor->width
  ^long [^Tensor tensor]
  (get (dimensions->most-rapidly-changing (tensor->dimensions tensor))))


(defn as-batch-matrix
  "As a 2d matrix of shape [batch-size everything-else]"
  ^Tensor [^Tensor tensor]
  (let [n-elems (ecount tensor)
        batch-size (tensor->batch-size tensor)]
    (reinterpret-tensor (:driver tensor)
                        (create-dimensions :height batch-size
                                           :width (quot n-elems
                                                        (long batch-size))))))


(defn as-2d-matrix
  "As a 2d matrix of shape [everything-else width]"
  ^Tensor [^Tensor tensor]
  (let [[n-rows n-cols] (tensor->2d-shape tensor)]
    (reinterpret-tensor (:driver tensor)
                        (create-dimensions :height n-rows
                                           :width n-cols))))

(defn as-dense
  ^Tensor [tensor]
  (when (dense? tensor)
    tensor))

(declare new-tensor)

(defn make-dense
  ^Tensor [^Tensor tensor]
  (or (as-dense tensor)
      (let [^Tensor retval (new-tensor [(ecount tensor)] :datatype (dtype/get-datatype tensor))]
        (mp/assign! retval tensor)
        (tensor (tensor->driver retval) (tensor->dimensions tensor) (tensor->buffer retval)))))

(defn copy-to-java-type
  [dest ^Tensor src]
  (resource/with-resource-context
   (let [tensor (make-dense src)
         n-elems (ecount tensor)
         driver (tensor->driver tensor)
         stream (check-stream)
         host-buffer (compute-drv/allocate-host-buffer driver n-elems
                                                       (dtype/get-datatype tensor))]
     (compute-drv/copy-device->host stream (tensor->buffer tensor) 0 host-buffer 0 n-elems)
     (compute-drv/wait-for-event (compute-drv/create-event stream))
     (dtype/copy! host-buffer 0 dest 0 n-elems)
     dest)))


(defn to-array-of-type
  [^Tensor tensor datatype]
  (copy-to-java-type (dtype/make-array-of-type datatype (ecount tensor))
                     tensor))


(defn to-double-array
  ^doubles [tensor]
  (to-array-of-type tensor :double))


(defn to-core-matrix
  [^Tensor tensor]
  (let [retval (m/new-array :vectorz (get (tensor->dimensions tensor) :shape))
        double-data (mp/as-double-array retval)]
    (copy-to-java-type double-data tensor)
    retval))

(defn to-core-matrix-vector
  [tensor]
  (m/as-vector (to-core-matrix tensor)))

(defn ->tensor
  "Create a tensor from the data.  The shape of the data combined with the batch size
will determine the shape of the outgoing tensor."
  [data & {:keys [datatype batch-size]
           :or {datatype *datatype*
                batch-size 1}}]
  (resource/with-resource-context
   (let [stream (check-stream)
         data-shape (m/shape data)
         n-elems (long (apply * data-shape))
         driver (compute-drv/get-driver stream)
         host-buffer (compute-drv/allocate-host-buffer driver n-elems datatype)
         dev-buffer (compute-drv/allocate-device-buffer driver n-elems datatype)
         dimensions (core-mat-shape->dimensions data-shape batch-size)]
     (dtype/copy-raw->item! data host-buffer 0)
     (compute-drv/copy-host->device stream host-buffer 0 dev-buffer 0 n-elems)
     ;;The wait here is so that we can clean up the host buffer.
     (compute-drv/wait-for-event (compute-drv/create-event stream))
     (tensor driver dimensions dev-buffer))))


(defn new-tensor
  [core-mshape & {:keys [datatype batch-size]
                  :or {datatype *datatype*
                       batch-size 1}}]
  (let [dimensions (core-mat-shape->dimensions shape batch-size)
        n-elems (long (apply * shape))
        stream (check-stream)
        driver (compute-drv/get-driver stream)
        dev-buffer (compute-drv/allocate-device-buffer driver n-elems datatype)
        driver (compute-drv/get-driver stream)]
    (compute-drv/memset stream dev-buffer 0 0 n-elems)
    (tensor driver dimensions dev-buffer)))


(defn subvector
  ^Tensor [^Tensor tensor offset & {:keys [length]}]
  (when-not-error (>= (long offset) 0)
    "Offset must be >= 0"
    {:offset offset})
  (let [vec-tensor (as-vector tensor)
        tens-ecount (ecount tensor)
        offset (long offset)
        new-len (long (or length
                          (- (ecount tensor) offset)))]
    (when (< new-len 0)
      (throw (ex-info "new length of tensor is <= 0"
                      {:tensor-ecount tens-ecount
                       :offset offset
                       :new-length new-len})))
    (let [new-buf (compute-drv/sub-buffer (tensor->driver tensor) (tensor->buffer tensor) offset new-len)]
      (tensor (tensor->driver tensor) (create-dimensions :width new-len) new-buf))))


(defn submatrix
  "Create a sub matrix of tensor.  Tensor will be interpreted as width being n-cols
and the rest of the dimensions being squashed into n-rows."
  ^Tensor [^Tensor tensor row-start row-length col-start col-length]
  (let [row-start (long row-start)
        row-length (long row-length)
        col-start (long col-start)
        col-length (long col-length)
        [n-rows n-cols] (dimensions->2d-shape (tensor->dimensions tensor))
        n-rows (long n-rows)
        n-cols (long n-cols)
        column-stride (tensor->column-stride tensor)
        driver (tensor->driver tensor)]
    (when (< row-start 0)
      (throw (ex-info "Row start less than 0" {})))
    (when (< col-start 0)
      (throw (ex-info "Col start less than 0" {})))
    (when (> (+ row-start row-length) n-rows)
      (throw (ex-info "Required row length out of bounds"
                      {:existing-row-length n-rows
                       :row-start row-start
                       :row-length row-length})))
    (when (> (+ col-start col-length) n-cols)
      (throw (ex-info "Required col length out of bounds"
                      {:existing-col-length n-cols
                       :col-start col-start
                       :col-length col-length})))
    (let [start-offset (+ (* column-stride row-start) col-start)
          required-length (* row-length column-stride)
          sub-buffer (compute-drv/sub-buffer driver (tensor->buffer tensor)
                                             start-offset required-length)]
      (tensor (tensor->driver tensor)
              (create-dimensions :width col-length
                                 :height row-length)
              (assoc (tensor->index-system tensor)
                     :num-columns col-length
                     :column-stride column-stride)
              sub-buffer))))


(defn- ensure-indexes
  "Index tensors must be integers and they must all be dense and the same length."
  [& args]
  (apply ensure-datatypes :int args)
  (when-not-error (every? dense? args)
    "Index tensors must be dense; some passed in are not." {})
  (let [first-len (ecount (first args))]
    (when-not-error (every? #(= first-len (ecount %)) (rest args))
      "Index tensors must all have matching element-counts"
      {:element-counts (map ecount args)}))
  (when-not-error (every? #(is/simple-monotonically-increasing? (tensor->index-system %))
                          args)
    "Indexes must be simply indexed which means simple monotonically increasing with no repetition."
    {:index-strategies (map tensor->index-system args)}))

(defn ensure-indexable-tensor
  [tensor]
  (when-not-error (is/simple-monotonically-increasing? (tensor->index-system tensor))
    "Cannot index members of non-monotonically increasing tensors."
    {:index-system (tensor->index-system tensor)}))


(defn index-columns
  "This returns a new tensor with the columns indexed by indexes.  Operations are restricted
to non-gemm operations."
  ^Tensor [^Tensor tensor ^Tensor indexes]
  (ensure-indexes indexes)
  (ensure-indexable-tensor tensor)
  (let [[n-rows n-cols] (tensor->2d-shape tensor)]
    (when-not-error (= n-cols (ecount indexes))
      "Index-ecount and num-columns mismatch"
      {:index-ecount (ecount indexes)
       :num-columns n-cols})
    (update tensor
            :index-system
            (fn [old-index-system]
              (is/update-index-system
               :strategy (is/indexed-strategy (tensor->buffer indexes))
               :elements-per-idx 1)))))


(defn indexed-columns?
  "Return true if this tensor has indexed columns"
  [tensor]
  (let [index-system (tensor->index-system tensor)
        [n-rows n-cols] (tensor->2d-shape tensor)
        n-rows (long n-rows)
        n-cols (long n-cols)]
    (and (is/indexed? index-system)
         (= 1 (is/elements-per-index index-system))
         (= n-cols
            (is/index-count index-system)))))


(defn index-rows
  "This returns a new tensor with the rows indexed by indexes.  Operations are restricted
to non-gemm operations."
  ^Tensor [^Tensor tensor ^Tensor indexes]
  (ensure-indexes indexes)
  (ensure-indexable-tensor tensor)
  (let [[n-rows n-cols] (tensor->2d-shape tensor)]
    (when-not-error (= n-rows (ecount indexes))
      "Index-ecount and num-rows mismatch"
      {:index-ecount (ecount indexes)
       :num-rows n-rows})
    (update tensor
            :index-system
            (fn [old-index-system]
              (is/update-index-system
               :strategy (is/indexed-strategy (tensor->buffer indexes))
               :elements-per-idx n-cols)))))


(defn indexed-rows?
  "Return true if this tensor has indexed rows"
  [tensor]
  (let [index-system (tensor->index-system tensor)
        [n-rows n-cols] (tensor->2d-shape tensor)
        n-rows (long n-rows)
        n-cols (long n-cols)]
    (and (is/indexed? index-system)
         (= n-cols (is/elements-per-index index-system))
         (= n-rows
            (is/index-count index-system)))))


(defn index-elements
  "This returns a new tensor with the elements indexed by indexes.  Operations are restricted
to non-gemm operations."
  ^Tensor [^Tensor tensor ^Tensor indexes]
  (ensure-indexes indexes)
  (ensure-indexable-tensor tensor)
  (let [n-elems (count tensor)]
    (when-not-error (= n-elems (ecount indexes))
      "Index-ecount and num-rows mismatch"
      {:index-ecount (ecount indexes)
       :num-elements n-elems})
    (update tensor
            :index-system
            (fn [old-index-system]
              (is/update-index-system
               :strategy (is/indexed-strategy (tensor->buffer indexes))
               :elements-per-idx 1)))))


(defn indexed-elements?
  "Return true if this tensor has indexed rows"
  [tensor]
  (let [index-system (tensor->index-system tensor)
        n-elems (ecount tensor)]
    (and (is/indexed? index-system)
         (= 1 (is/elements-per-index index-system))
         (= n-elems
            (is/index-count index-system)))))


(defn rows
  "Returns a vector rows of dense vectors."
  [^Tensor tensor]
  (let [[n-rows n-cols] (as-2d-matrix tensor)
        column-stride (tensor->column-stride tensor)
        driver (tensor->driver tensor)
        buffer (tensor->buffer tensor)]
    (mapv (fn [^long idx]
            (let [offset (* idx column-stride)
                  new-buf (compute-drv/sub-buffer driver buffer offset n-cols)]
              (tensor driver (create-dimensions :width n-cols) new-buf)))
          (range n-rows))))


(defn columns
  "Returns a vector of matrixes with width of 1 but large column strides."
  [^Tensor tensor]
  (let [[n-rows n-cols] (as-2d-matrix tensor)
        column-stride (tensor->column-stride tensor)
        driver (tensor->driver tensor)
        buffer (tensor->buffer tensor)
        col-required-mem (* (- (long n-rows) 1) column-stride)
        buf-ecount (ecount buffer)]
    (mapv (fn [^long offset]
            (let [new-buf (compute-drv/sub-buffer driver buffer offset (- buf-ecount offset))]
              (tensor driver (create-dimensions :width n-rows)
                             1
                             column-stride new-buf)))
          (range n-cols))))


(defn- ensure-indexed-op
  [^Tensor dest ^Tensor dest-indexes ^Tensor src ^Tensor src-indexes]
  (ensure-indexes dest-indexes src-indexes)
  (let [[dest-rows dest-cols] (dimensions->2d-shape (tensor->dimensions dest))
        [src-rows src-cols] (dimensions->2d-shape (tensor->dimensions src))
        n-dest-elems (* (long dest-cols) (ecount dest-indexes))
        n-src-elems (* (long src-cols) (ecount src-indexes))
        min-n-elems (long (min n-dest-elems n-src-elems))
        max-n-elems (long (max n-dest-elems n-src-elems))]
    (when-not-error (or (= 0 min-n-elems)
                        (= 0 (rem max-n-elems
                                  min-n-elems)))
      "Indexed operations must be commensurate"
      {:min-n-elems min-n-elems
       :max-n-elems max-n-elems
       :remainder (rem max-n-elems min-n-elems)})
    (ensure-same-device dest dest-indexes src src-indexes)))


(defn simple-tensor?
  "A simple tensor is one that can be copied or assigned to with memset or memcpy (not memmove)
  semantics."
  [tensor]
  (and (dense? tensor)
       (= :monotonically-increasing
          (is/system->strategy-type (tensor->index-system tensor)))
       (= (ecount tensor)
          (is/system->index-length (tensor->index-system tensor)))))


(defmethod typed-assign! [:tensor :number]
  [^Tensor dest src]
  (if (simple-tensor? dest)
    (compute-drv/memset (check-stream) (tensor->buffer tensor) 0 src (ecount tensor))
    (tm/assign-constant! (check-stream)
                         (tensor->buffer tensor) (tensor->index-system tensor) (ecount tensor)
                         src)))


(defn- memcpy-semantics?
  [dest src]
  (and (= (ecount dest) (ecount src))
       (simple-tensor? dest)
       (simple-tensor? src)
       (= (get-datatype dest)
          (get-datatype src))))


(defn- ensure-memcpy-semantics
  [dest src]
  (when-not-error (= (ecount dest) (ecount src))
    "inter-device operations must have simple memory semants"
    {:dest-ecount (ecount dest)
     :src-ecount (ecount src)})
  (when-not-error (and (simple-tensor? dest)
                       (simple-tensor? src))
    "Inter-device operations must have simple (mono-increasing and dense) address strategies"
    {:simple-dest? (simple-tensor? dest)
     :simple-src? (simple-tensor? src)})
  (when-not-error (= (get-datatype src)
                     (get-datatype dest))
    "Inter-device operations must match datatypes."
    {:dest-datatype (get-datatype dest)
     :src-datatype (get-datatype src)}))


(defmethod typed-assign! [:tensor :tensor]
  [^Tensor dest ^Tensor src]
  (let [dest-ecount (ecount dest)
        src-ecount (ecount src)]
    (when-not-error (>= dest-ecount
                        src-ecount)
      "destination element count must be >= src element count"
      {:dest-ecount dest-ecount
       :src-count src-ecount})
    (when-not-error (element-counts-commensurate? dest-ecount src-ecount)
      "Src element count must evenly divide dest ecount."
      {:dest-ecount dest-ecount
       :src-ecount src-ecount})
    (ensure-same-driver dest src)
    (check-partial-alias dest src)
    (if (memcpy-semantics? dest src)
      (compute-drv/copy-device->device (check-stream)
                                       (tensor->buffer src) 0
                                       (tensor->buffer dest) 0
                                       (ecount src))
      (do
        (ensure-same-device dest src)
        (tm/assign! (check-stream)
                    (tensor->buffer dest) (tensor->index-system dest) (ecount dest)
                    (tensor->buffer src) (tensor->index-system src) (ecount src))))))


(extend-type Tensor
  mp/PVectorView
  (as-vector [m]
    (reinterpret-tensor m (create-dimensions :width (ecount m))))

  mp/PVectorisable
  (to-vector [m]
    (mp/as-vector m))

  mp/PAssignment
  (assign! [dest src]
    (typed-assign! dest src)))