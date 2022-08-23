(ns toucan2.instance
  (:refer-clojure :exclude [instance?])
  (:require
   [clojure.data :as data]
   [methodical.core :as m]
   [potemkin :as p]
   [pretty.core :as pretty]
   [toucan2.magic-map :as magic-map]
   [toucan2.protocols :as protocols]
   [toucan2.realize :as realize]
   [toucan2.util :as u]))

(set! *warn-on-reflection* true)

(defn instance?
  "True if `x` is a Toucan2 instance, i.e. a `toucan2.instance.Instance` or some other class that satisfies the correct
  interfaces.

  Toucan instances need to implement [[IModel]], [[IWithModel]], and [[IRecordChanges]]."
  [x]
  (every? #(clojure.core/instance? % x)
          [toucan2.protocols.IModel
           toucan2.protocols.IWithModel
           toucan2.protocols.IRecordChanges]))

(defn instance-of?
  "True if `x` is a Toucan2 instance, and its [[protocols/model]] `isa?` `model`.

    (instance-of? ::bird (instance ::toucan {})) ; -> true
    (instance-of?  ::toucan (instance ::bird {})) ; -> false"
  [model x]
  (and (instance? x)
       (isa? (protocols/model x) model)))

(declare ->TransientInstance)

(p/def-map-type Instance [model
                          ^clojure.lang.IPersistentMap orig
                          ^clojure.lang.IPersistentMap m
                          mta]
  (get [_ k default-value]
    (get m k default-value))

  (assoc [this k v]
    (let [new-m (assoc m k v)]
      (if (identical? m new-m)
        this
        (Instance. model orig new-m mta))))

  (dissoc [this k]
    (let [new-m (dissoc m k)]
      (if (identical? m new-m)
        this
        (Instance. model orig new-m mta))))

  (keys [_this]
    (keys m))

  (meta [_this]
    mta)

  (with-meta [this new-meta]
    (if (identical? mta new-meta)
      this
      (Instance. model orig m new-meta)))

  clojure.lang.IPersistentCollection
  (equiv [_this another]
    (cond
      (clojure.core/instance? toucan2.protocols.IModel another)
      (and (= model (protocols/model another))
           (= m another))

      (map? another)
      (= m another)

      :else
      false))

  java.util.Map
  (containsKey [_this k]
    (.containsKey m k))

  clojure.lang.IEditableCollection
  (asTransient [_this]
    (->TransientInstance model (transient m) mta))

  protocols/IModel
  (protocols/model [_this]
    model)

  protocols/IWithModel
  (with-model [this new-model]
    (if (= model new-model)
      this
      (Instance. new-model orig m mta)))

  protocols/IRecordChanges
  (original [_this]
    orig)

  (with-original [this new-original]
    (if (identical? orig new-original)
      this
      (let [new-original (if (nil? new-original)
                           {}
                           new-original)]
        (assert (map? new-original))
        (Instance. model new-original m mta))))

  (current [_this]
    m)

  (with-current [this new-current]
    (if (identical? m new-current)
      this
      (let [new-current (if (nil? new-current)
                          {}
                          new-current)]
        (assert (map? new-current))
        (Instance. model orig new-current mta))))

  (changes [_this]
    (not-empty (second (data/diff orig m))))

  protocols/DispatchValue
  (dispatch-value [_this]
    (protocols/dispatch-value model))

  realize/Realize
  (realize [_this]
    (if (identical? orig m)
      (let [m (realize/realize m)]
        (Instance. model m m mta))
      (Instance. model (realize/realize orig) (realize/realize m) mta)))

  pretty/PrettyPrintable
  (pretty [_this]
    (list `instance model m)))

(deftype TransientInstance [model ^clojure.lang.ITransientMap m mta]
  clojure.lang.ITransientMap
  (conj [this v]
    (.conj m v)
    this)
  (persistent [_this]
    (let [m (persistent! m)]
      (Instance. model m m mta)))
  (assoc [this k v]
    (.assoc m k v)
    this)
  (without [this k]
    (.without m k)
    this)

  pretty/PrettyPrintable
  (pretty [_this]
    (list `->TransientInstance model m mta)))

(m/defmulti key-transform-fn
  "Function to use to magically transform map keywords when building a new instance of `model`."
  {:arglists '([model])}
  u/dispatch-on-first-arg)

(m/defmethod key-transform-fn :default
  [_model]
  magic-map/*key-transform-fn*)

(m/defmulti empty-map
  "Return an empty map that should be used as the basis for creating new instances of a model. You can provide a custom
  implementation if you want to use something other than the default [[toucan2.magic-map]] implementation."
  {:arglists '([model])}
  u/dispatch-on-first-arg)

(m/defmethod empty-map :default
  [model]
  (magic-map/magic-map {} (key-transform-fn model)))

(defn instance
  (^toucan2.instance.Instance [model]
   (instance model (empty-map model)))

  (^toucan2.instance.Instance [model m]
   (let [m* (into (empty-map model) m)]
     (->Instance model m* m* (meta m))))

  (^toucan2.instance.Instance [model k v & more]
   (let [m (into (empty-map model) (partition-all 2) (list* k v more))]
     (instance model m))))

(extend-protocol protocols/IWithModel
  nil
  (with-model [_this model]
    (instance model))

  clojure.lang.IPersistentMap
  (with-model [m model]
    (instance model m)))

(defn reset-original
  "Return a copy of `instance` with its `original` value set to its current value, discarding the previous original
  value. No-ops if `instance` is not a Toucan 2 instance."
  [instance]
  (if (instance? instance)
    (protocols/with-original instance (protocols/current instance))
    instance))

;;; TODO -- should we have a revert-changes helper function as well?

(defn update-original
  "Applies `f` directly to the underlying `original` map of an `instance`. No-ops if `instance` is not an `Instance`."
  [instance f & args]
  (if (instance? instance)
    (protocols/with-original instance (apply f (protocols/original instance) args))
    instance))

(defn update-current
  "Applies `f` directly to the underlying `current` map of an `instance`; useful if you need to operate on it directly.
  Acts like regular `(apply f instance args)` if `instance` is not an `Instance`."
  [instance f & args]
  (protocols/with-current instance (apply f (protocols/current instance) args)))

(defn update-original-and-current
  "Like `(apply f instance args)`, but affects both the `original` map and `current` map of `instance` rather than just
  the current map. Acts like regular `(apply f instance args)` if `instance` is not an `Instance`.

  `f` is applied directly to the underlying `original` and `current` maps of `instance` itself. `f` is only applied
  once if `original` and `current` are currently the same object (i.e., the new `original` and `current` will also be
  the same object). If `current` and `original` are not the same object, `f` is applied twice."
  [instance f & args]
  (if (identical? (protocols/original instance) (protocols/current instance))
    (reset-original (apply update-current instance f args))
    (as-> instance instance
      (apply update-original instance f args)
      (apply update-current  instance f args))))
