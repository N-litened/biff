(ns com.biffweb.impl.xtdb
  (:require [better-cond.core :as b]
            [com.biffweb.impl.util.ns :as ns]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [xtdb.api :as xt]
            [malli.error :as male]
            [malli.core :as malc]))

(defn start-node
  [{:keys [topology dir opts jdbc-spec pool-opts kv-store]
    :or {kv-store :rocksdb}}]
  (let [kv-store-fn (fn [basename]
                      {:kv-store {:xtdb/module (if (= kv-store :lmdb)
                                                 'xtdb.lmdb/->kv-store
                                                 'xtdb.rocksdb/->kv-store)
                                  :db-dir (io/file dir (str basename (when (= kv-store :lmdb)
                                                                       "-lmdb")))}})
        node (xt/start-node
              (merge (case topology
                       :standalone
                       {:xtdb/index-store    (kv-store-fn "index")
                        :xtdb/document-store (kv-store-fn "docs")
                        :xtdb/tx-log         (kv-store-fn "tx-log")}

                       :jdbc
                       {:xtdb/index-store (kv-store-fn "index")
                        :xtdb.jdbc/connection-pool {:dialect {:xtdb/module
                                                              'xtdb.jdbc.psql/->dialect}
                                                    :pool-opts pool-opts
                                                    :db-spec jdbc-spec}
                        :xtdb/tx-log {:xtdb/module 'xtdb.jdbc/->tx-log
                                      :connection-pool :xtdb.jdbc/connection-pool}
                        :xtdb/document-store {:xtdb/module 'xtdb.jdbc/->document-store
                                              :connection-pool :xtdb.jdbc/connection-pool}})
                     opts))
        f (future (xt/sync node))]
    (while (not (realized? f))
      (Thread/sleep 2000)
      (when-some [indexed (xt/latest-completed-tx node)]
        (log/info "Indexed" (pr-str indexed))))
    node))

(defn use-xt
  [{:biff.xtdb/keys [topology dir kv-store opts]
    :or {kv-store :rocksdb}
    :as sys}]
  (let [node (start-node
              {:topology topology
               :dir dir
               :kv-store kv-store
               :opts opts
               :jdbc-spec (ns/select-ns-as sys 'biff.xtdb.jdbc nil)
               :pool-opts (ns/select-ns-as sys 'biff.xtdb.jdbc-pool nil)})]
    (-> sys
        (assoc :biff.xtdb/node node)
        (update :biff/stop conj #(.close node)))))

(defn use-tx-listener [{:keys [biff.xtdb/on-tx biff.xtdb/node] :as sys}]
  (if-not on-tx
    sys
    (let [lock (Object.)
          listener (xt/listen
                    node
                    {::xt/event-type ::xt/indexed-tx}
                    (fn [{:keys [::xt/tx-id committed?]}]
                      (when committed?
                        (locking lock
                          (with-open [log (xt/open-tx-log node
                                                          (dec tx-id)
                                                          true)]
                            (let [tx (first (iterator-seq log))]
                              (try
                                (on-tx sys tx)
                                (catch Exception e
                                  (log/error e "Exception during on-tx")))))))))]
      (update sys :biff/stop conj #(.close listener)))))

(defn assoc-db [{:keys [biff.xtdb/node] :as sys}]
  (assoc sys :biff/db (xt/db node)))

(defn q [db query & args]
  (when-not (= (count (:in query))
               (count args))
    (throw (ex-info (str "Incorrect number of query arguments. Expected "
                         (count (:in query))
                         " but got "
                         (count args)
                         ".")
                    {})))
  (let [return-tuples (vector? (:find query))
        query (cond-> query
                (not return-tuples) (update :find vector))
        results (apply xt/q db query args)]
    (cond->> results
      (not return-tuples) (map first))))

(defn lazy-q
  [db query & args]
  (when-not (= (count (:in query))
               (dec (count args)))
    (throw (ex-info (str "Incorrect number of query arguments. Expected "
                         (count (:in query))
                         " but got "
                         (count args)
                         ".")
                    {})))
  (let [f (last args)
        query-args (butlast args)
        return-tuples (vector? (:find query))
        query (cond-> query
                (not return-tuples) (update :find vector))]
    (with-open [results (apply xt/open-q db query query-args)]
      (f (cond->> (iterator-seq results)
           (not return-tuples) (map first))))))

(defn lookup [db k v]
  (ffirst (xt/q db {:find '[(pull doc [*])]
                    :where [['doc k v]]})))

(defn lookup-id [db k v]
  (ffirst (xt/q db {:find '[doc]
                    :where [['doc k v]]})))

(defn- special-val? [x]
  (or (= x :db/dissoc)
      (and (coll? x)
           (<= 2 (count x))
           (#{:db/lookup
              :db/union
              :db/difference
              :db/add
              :db/default} (first x)))))

(defn- apply-special-vals [doc-before doc-after]
  (->> (merge doc-before doc-after)
       (keep (fn [[k v]]
               (b/cond
                 (not (special-val? v)) [k v]
                 (= v :db/dissoc) nil
                 :let [[op & xs] v
                       v-before (get doc-before k)]
                 (= op :db/union) [k (set/union (set v-before) (set xs))]
                 (= op :db/difference) [k (set/difference (set v-before) (set xs))]
                 (= op :db/add) [k (apply + (or v-before 0) xs)]
                 :let [[default-value] xs]
                 (= op :db/default) (if (contains? doc-before k)
                                      v-before
                                      default-value))))
       (into {})))

(b/defnc lookup-info [db doc-id]
  :let [[lookup-id default-id] (when (and (special-val? doc-id)
                                          (= :db/lookup (first doc-id)))
                                 (rest doc-id))]
  :when lookup-id
  :let [lookup-doc-before (xt/entity db lookup-id)
        lookup-doc-after (or lookup-doc-before
                             {:xt/id lookup-id
                              :db/owned-by (or default-id (java.util.UUID/randomUUID))})]
  [lookup-id lookup-doc-before lookup-doc-after])

(b/defnc get-ops
  [{:keys [::now biff/db biff/malli-opts]}
   {:keys [xt/id db/doc-type db/op] :or {op :put} :as tx-doc}]
  ;; possible ops: delete, put, merge, update
  :let [valid? (fn [doc] (malc/validate doc-type doc @malli-opts))
        explain (fn [doc] (male/humanize (malc/explain doc-type doc @malli-opts)))
        [lookup-id
         lookup-doc-before
         lookup-doc-after] (lookup-info db id)
        id (if lookup-id
             (:db/owned-by lookup-doc-after)
             (or id (java.util.UUID/randomUUID)))]
  (= op :delete) (concat [[::xt/delete id]]
                         (when lookup-id
                           [[::xt/match lookup-id lookup-doc-before]
                            [::xt/delete lookup-id]]))

  ;; possible ops: put, merge, update
  (nil? doc-type) (throw (ex-info "Missing :db/doc-type."
                                  {:tx-doc tx-doc}))
  :let [doc-after (cond-> tx-doc
                    (map? lookup-id) (merge lookup-id)
                    true (dissoc :db/op :db/doc-type)
                    true (assoc :xt/id id))
        doc-after (walk/postwalk #(if (= % :db/now) now %) doc-after)
        lookup-ops (when lookup-id
                     [[::xt/match lookup-id lookup-doc-before]
                      [::xt/put lookup-doc-after]])]
  :do (cond
        (not= op :put) nil,

        (some special-val? (vals doc-after))
        (throw (ex-info "Attempted to use a special value on a :put operation"
                        {:tx-doc tx-doc})),

        (not (valid? doc-after))
        (throw (ex-info (str "Doc wouldn't be a valid " doc-type " after transaction.")
                        {:tx-doc tx-doc
                         :explain (explain doc-after)})))
  (= op :put) (concat [[::xt/put doc-after]] lookup-ops)

  ;; possible ops: merge, update
  :let [doc-before (xt/entity db id)]
  (and (= op :update)
       (nil? doc-before)) (throw (ex-info "Attempted to update on a new doc."
                                          {:tx-doc tx-doc}))
  :let [doc-after (apply-special-vals doc-before doc-after)]
  (not (valid? doc-after)) (throw (ex-info (str "Doc wouldn't be a valid " doc-type " after transaction.")
                                           {:tx-doc tx-doc
                                            :explain (explain doc-after)}))
  :else (concat [[::xt/match id doc-before]
                 [::xt/put doc-after]]
                lookup-ops))

(b/defnc submit-tx
  [{:keys [biff.xtdb/node
           biff.xtdb/n-tried]
    :or {n-tried 0}
    :as sys}
   biff-tx]
  :let [sys (assoc (assoc-db sys) ::now (java.util.Date.))
        xt-tx (mapcat #(get-ops sys %) biff-tx)
        submitted-tx (xt/submit-tx node xt-tx)]
  :do (xt/await-tx node submitted-tx)
  (xt/tx-committed? node submitted-tx) submitted-tx
  (<= 4 n-tried) (throw (ex-info "TX failed, too much contention." {:tx biff-tx}))
  :let [seconds (int (Math/pow 2 n-tried))]
  :do (log/warnf "TX failed due to contention, trying again in %d seconds...\n"
                 seconds)
  :do (flush)
  :do (Thread/sleep (* 1000 seconds))
  (recur (update sys :biff.xtdb/n-tried (fnil inc 0)) biff-tx))
