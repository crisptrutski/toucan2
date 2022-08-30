(ns toucan2.select
  (:refer-clojure :exclude [count])
  (:require
   [methodical.core :as m]
   [toucan2.model :as model]
   [toucan2.pipeline :as pipeline]
   [toucan2.query :as query]
   [toucan2.realize :as realize]
   [toucan2.util :as u]))

(m/defmethod pipeline/transduce-resolved-query [#_query-type :toucan.query-type/select.*
                                                #_model      :default
                                                #_query      clojure.lang.IPersistentMap]
  [rf query-type model {:keys [columns], :as parsed-args} resolved-query]
  (let [parsed-args    (dissoc parsed-args :columns)
        resolved-query (merge {:select (or (not-empty columns)
                                           [:*])}
                              (when model
                                {:from [(query/honeysql-table-and-alias model)]})
                              resolved-query)]
    (next-method rf query-type model parsed-args resolved-query)))

(defn reducible-select
  {:arglists '([modelable & kv-args? query?]
               [[modelable & columns] & kv-args? query?])}
  [& unparsed-args]
  (pipeline/reducible-unparsed :toucan.query-type/select.instances unparsed-args))

(defn select
  {:arglists '([modelable & kv-args? query?]
               [[modelable & columns] & kv-args? query?])}
  [& unparsed-args]
  (pipeline/transduce-unparsed-with-default-rf :toucan.query-type/select.instances unparsed-args))

(defn select-one {:arglists '([modelable & kv-args? query?]
                              [[modelable & columns] & kv-args? query?])}
  [& unparsed-args]
  (pipeline/transduce-unparsed-first-result :toucan.query-type/select.instances unparsed-args))

(defn select-fn-reducible
  {:arglists '([f modelable & kv-args? query?])}
  [f & args]
  (eduction
   (map f)
   (apply reducible-select args)))

(defn select-fn-set
  "Like `select`, but returns a set of values of `(f instance)` for the results. Returns `nil` if the set is empty."
  {:arglists '([f modelable & kv-args? query?])}
  [& args]
  (not-empty (reduce conj #{} (apply select-fn-reducible args))))

(defn select-fn-vec
  "Like `select`, but returns a vector of values of `(f instance)` for the results. Returns `nil` if the vector is
  empty."
  {:arglists '([f modelable & kv-args? query?])}
  [& args]
  (not-empty (reduce conj [] (apply select-fn-reducible args))))

(defn select-one-fn
  {:arglists '([f modelable & kv-args? query?])}
  [& args]
  (realize/reduce-first (apply select-fn-reducible args)))

(defn select-pks-reducible
  {:arglists '([modelable & kv-args? query?])}
  [modelable & args]
  (let [f (model/select-pks-fn modelable)]
    (apply select-fn-reducible f modelable args)))

(defn select-pks-set
  {:arglists '([modelable & kv-args? query?])}
  [& args]
  (not-empty (reduce conj #{} (apply select-pks-reducible args))))

(defn select-pks-vec
  {:arglists '([modelable & kv-args? query?])}
  [& args]
  (not-empty (reduce conj [] (apply select-pks-reducible args))))

(defn select-one-pk
  {:arglists '([modelable & kv-args? query?])}
  [& args]
  (realize/reduce-first (apply select-pks-reducible args)))

(defn select-fn->fn
  {:arglists '([f1 f2 modelable & kv-args? query?])}
  [f1 f2 & args]
  (not-empty
   (into
    {}
    (map (juxt f1 f2))
    (apply reducible-select args))))

(defn select-fn->pk
  {:arglists '([f modelable & kv-args? query?])}
  [f modelable & args]
  (let [pks-fn (model/select-pks-fn modelable)]
    (apply select-fn->fn f pks-fn modelable args)))

(defn select-pk->fn
  {:arglists '([f modelable & kv-args? query?])}
  [f modelable & args]
  (let [pks-fn (model/select-pks-fn modelable)]
    (apply select-fn->fn pks-fn f modelable args)))

;;; TODO -- [[count]] and [[exists?]] implementations seem kinda dumb, maybe we should just hand off to
;;; [[reducible-select]] by default so it can handle the parsing and stuff.

(m/defmulti count*
  {:arglists '([model unparsed-args])}
  u/dispatch-on-first-arg)

(m/defmethod count* :default
  [model unparsed-args]
  (u/println-debug ["No efficient implementation of count* for %s, doing reducible-select and counting the rows..." model])
  (reduce
   (fn [acc _]
     (inc acc))
   0
   (apply reducible-select model unparsed-args)))

(defn count
  {:arglists '([modelable & kv-args? query?])}
  [modelable & unparsed-args]
  (model/with-model [model modelable]
    (count* model unparsed-args)))

(m/defmulti exists?*
  {:arglists '([model unparsed-args])}
  u/dispatch-on-first-arg)

(m/defmethod exists?* :default
  [model unparsed-args]
  (u/println-debug ["No efficient implementation of exists?* for %s, doing reducible-select and seeing if it returns a row..." model])
  (transduce
   (take 1)
   (fn
     ([acc]
      acc)
     ([_ _]
      true))
   false
   (apply reducible-select model unparsed-args)))

(defn exists?
  {:arglists '([modelable & kv-args? query?])}
  [modelable & unparsed-args]
  (model/with-model [model modelable]
    (exists?* model unparsed-args)))
