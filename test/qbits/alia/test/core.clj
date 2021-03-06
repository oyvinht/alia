(ns qbits.alia.test.core
  (:require
   [clojure.test :refer :all]
   [clojure.data :refer :all]
   [clj-time.core :as t]
   [qbits.alia :refer :all]
   [qbits.alia.manifold :as ma]
   [qbits.alia.async :refer [execute-chan execute-chan-buffered]]
   [qbits.alia.codec :refer :all]
   [qbits.alia.codec.default :refer :all]
   [qbits.alia.codec.udt-aware]
   [qbits.alia.codec.extension.joda-time :refer :all]
   [qbits.hayt :as h]
   [clojure.core.async :as async])
  (:import
    (com.datastax.driver.core Statement UDTValue ConsistencyLevel Cluster)))

(try
  (require 'qbits.alia.spec)
  (require 'clojure.spec.test.alpha)
  ((resolve 'clojure.spec.test.alpha/instrument))
  (println "Instrumenting qbits.alia with clojure.spec")
  (catch Exception e
    (.printStackTrace e)))

(def ^:dynamic *cluster*)
(def ^:dynamic *session*)

;; some test data
(def user-data-set [{:created (t/date-time 2012 4 18 11 23 12 798)
                     :tuuid #uuid "e34288d0-7617-11e2-9243-0024d70cf6c4",
                     :last_name "Penet",
                     :emails #{"m@p.com" "ma@pe.com"},
                     :tags [1 2 3],
                     :first_name "Max",
                     :amap {"foo" 1, "bar" 2},
                     :auuid #uuid "42048d2d-c135-4c18-aa3a-e38a6d3be7f1",
                     :valid true,
                     :birth_year 0,
                     :user_name "mpenet"
                     :tup ["a", "b"]
                     :udt {:foo "f" :bar 100}}
                    {:created (t/date-time 2012 4 18 11 23 12 798)
                     :tuuid #uuid "e34288d0-7617-11e2-9243-0024d70cf6c4",
                     :last_name "Baggins",
                     :emails #{"baggins@gmail.com" "f@baggins.com"},
                     :tags [4 5 6],
                     :first_name "Frodo",
                     :amap {"foo" 1, "bar" 2},
                     :auuid #uuid "1f84b56b-5481-4ee4-8236-8a3831ee5892",
                     :valid true,
                     :birth_year 1,
                     :user_name "frodo"
                     :tup ["a", "b"]
                     :udt {:foo "f" :bar 100}}])

;; helpers

(def sort-result (partial sort-by :user_name))

(use-fixtures
  :once
  (fn [test-runner]
    ;; prepare the thing
    (binding [*cluster* (cluster {:contact-points ["127.0.0.1"]})]
      (binding [*session* (connect *cluster*)]
        (try (execute *session* "DROP KEYSPACE alia;")
             (catch Exception _ nil))
        (execute *session* "CREATE KEYSPACE alia WITH replication = {'class': 'SimpleStrategy', 'replication_factor' : 1};")
        (execute *session* "USE alia;")
        (execute *session* "CREATE TYPE udt (
                                foo text,
                                bar bigint
                           )")

        (execute *session* "CREATE TYPE udtct (
                                foo text,
                                tup frozen<tuple<varchar, varchar>>
                           )")
        (execute *session* "CREATE TABLE users (
                user_name varchar,
                first_name varchar,
                last_name varchar,
                auuid uuid,
                tuuid timeuuid,
                birth_year bigint,
                created timestamp,
                valid boolean,
                emails set<text>,
                tags list<bigint>,
                amap map<varchar, bigint>,
                tup frozen<tuple<varchar, varchar>>,
                udt frozen<udt>,
                PRIMARY KEY (user_name)
              );")
        (execute *session* "CREATE INDEX ON users (birth_year);")

        (execute *session* "INSERT INTO users (user_name, first_name, last_name, emails, birth_year, amap, tags, auuid, tuuid, valid, tup, udt, created)
       VALUES('frodo', 'Frodo', 'Baggins', {'f@baggins.com', 'baggins@gmail.com'}, 1, {'foo': 1, 'bar': 2}, [4, 5, 6], 1f84b56b-5481-4ee4-8236-8a3831ee5892, e34288d0-7617-11e2-9243-0024d70cf6c4, true, ('a', 'b'),  {foo: 'f', bar: 100}, '2012-04-18T11:23:12.798-00:00');")
        (execute *session* "INSERT INTO users (user_name, first_name, last_name, emails, birth_year, amap, tags, auuid, tuuid, valid, tup, udt, created)
       VALUES('mpenet', 'Max', 'Penet', {'m@p.com', 'ma@pe.com'}, 0, {'foo': 1, 'bar': 2}, [1, 2, 3], 42048d2d-c135-4c18-aa3a-e38a6d3be7f1, e34288d0-7617-11e2-9243-0024d70cf6c4, true, ('a', 'b'), {foo: 'f', bar: 100}, '2012-04-18T11:23:12.798-00:00');")


        (execute *session* "CREATE TABLE items (
                    id int,
                    si int,
                    text varchar,
                    PRIMARY KEY (id)
                  );")

        (execute *session* "CREATE INDEX ON items (si);")

        (dotimes [i 10]
          (execute *session* (format "INSERT INTO items (id, text, si) VALUES(%s, 'prout', %s);" i i)))

        (execute *session* "CREATE TABLE simple (
                    id int,
                    text varchar,
                    PRIMARY KEY (id)
                  );")

        ;; do the thing
        (test-runner)

        ;; destroy the thing
        (try (execute *session* "DROP KEYSPACE alia;") (catch Exception _ nil))
        (shutdown *session*)
        (shutdown *cluster*)))))

(deftest test-cluster-query-options
  (let [cluster (.init ^Cluster (cluster {:contact-points ["127.0.0.1"]
                                          :query-options  {:consistency        :local-quorum
                                                           :serial-consistency :three
                                                           :fetch-size         12345}}))]

    (is (= ConsistencyLevel/LOCAL_QUORUM (.getConsistencyLevel
                                           (.getQueryOptions
                                             (.getConfiguration
                                               cluster)))))

    (is (= ConsistencyLevel/THREE (.getSerialConsistencyLevel
                                    (.getQueryOptions
                                      (.getConfiguration
                                        cluster)))))

    (is (= 12345 (.getFetchSize
                   (.getQueryOptions
                     (.getConfiguration
                       cluster)))))))

(deftest test-sync-execute
  (is (= user-data-set
         (qbits.alia/execute *session* "select * from users;")))

  (is (= user-data-set
         (execute *session* (h/select :users)))))

(deftest test-manifold-execute
  ;; promise
  (is (= user-data-set
         @(ma/execute *session* "select * from users;"))))

(deftest test-core-async-execute
  (is (= user-data-set
         (async/<!! (execute-chan *session* "select * from users;"))))

  (let [p (promise)]
    (execute-async *session* "select * from users;"
                   {:success (fn [r] (deliver p r))})
    (is (= user-data-set @p)))

  (let [p (promise)]
    (async/take! (execute-chan *session* "select * from users;")
                 (fn [r] (deliver p r)))
    (is (= user-data-set @p)))

;;   ;; Something smarter could be done with alt! (select) but this will
;;   ;; do for a test
  (is (= 3 (count (async/<!! (async/go
                               (loop [i 0 ret []]
                                 (if (= 3 i)
                                   ret
                                   (recur (inc i)
                                          (conj ret (async/<! (execute-chan *session* "select * from users limit 1"))))))))))))

(deftest test-prepared
  (let [s-simple (prepare *session* "select * from users;")
        s-parameterized-simple (prepare *session* (h/select :users (h/where {:user_name h/?})))
        s-parameterized-in (prepare *session* (h/select :users (h/where [[:in :user_name h/?]])))
        s-prepare-types (prepare *session*  "INSERT INTO users (user_name, birth_year, auuid, tuuid, created, valid, tags, emails, amap) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")
        ;; s-parameterized-set (prepare  "select * from users where emails=?;")
        ;; s-parameterized-nil (prepare  "select * from users where session_token=?;")
        ]
    (is (= user-data-set (execute *session* s-simple)))
    (is (= [(first user-data-set)] (execute *session* (h/select :users (h/where {:user_name h/?}))
                                            {:values ["mpenet"]})))
    (is (= (sort-result user-data-set) (sort-result (execute *session* s-parameterized-in {:values [["mpenet" "frodo"]]}))))
    (is (= [(first user-data-set)]
           (execute *session* s-parameterized-simple {:values ["mpenet"]})))
    ;; manually  bound
    (is (= [(first user-data-set)]
           (execute *session* (bind s-parameterized-simple ["mpenet"]))))

    (is (= [] (execute *session* s-prepare-types {:values ["foobar"
                                                           0
                                                           #uuid "b474e171-7757-449a-87be-d2797d1336e3"
                                                           #uuid "e34288d0-7617-11e2-9243-0024d70cf6c4"
                                                           (java.util.Date.)
                                                           false
                                                           [1 2 3 4]
                                                           #{"foo" "bar"}
                                                           {"foo" 123}]})))
    (let [delete-q "delete from users where user_name = 'foobar';"]
      (is (= ()
             (execute *session* (batch (repeat 3 delete-q))))))


    (is (= [] (try (execute *session* s-prepare-types {:values {:user_name "barfoo"
                                                            :birth_year 0
                                                            :auuid #uuid "b474e171-7757-449a-87be-d2797d1336e3"
                                                            :tuuid #uuid "e34288d0-7617-11e2-9243-0024d70cf6c4"
                                                            :created (java.util.Date.)
                                                            :valid false
                                                            :tags [1 2 3 4]
                                                            :emails  #{"foo" "bar"}
                                                            :amap {"foo" 123}}})
                   (catch Exception e (.printStackTrace e)))))
    (let [delete-q "delete from users where user_name = 'barfoo';"]
      (is (= ()
             (execute *session* (batch (repeat 3 delete-q))))))

    (is (= [] (try (execute *session* s-prepare-types {:values {:user_name "ffoooobbaarr"
                                                            :birth_year 0
                                                            :auuid #uuid "b474e171-7757-449a-87be-d2797d1336e3"
                                                            :tuuid #uuid "e34288d0-7617-11e2-9243-0024d70cf6c4"
                                                            :created (t/now)
                                                            :valid false
                                                            :tags [1 2 3 4]
                                                            :emails  #{"foo" "bar"}
                                                            :amap {"foo" 123}}})
                   (catch Exception e (.printStackTrace e)))))

    (let [delete-q "delete from users where user_name = 'ffoooobbaarr';"]
      (is (= ()
             (execute *session* (batch (repeat 3 delete-q))))))))

(deftest test-error
  (let [stmt "slect prout from 1;"]
    (is (:query (try (execute *session* stmt)
                     (catch Exception ex
                       (ex-data ex)))))

    (is (:query (try @(prepare *session* stmt)
                     (catch Exception ex
                       (ex-data ex)))))

    (let [stmt "select * from foo where bar = ?;" values [1 2]]
      (is (:query (try @(bind (prepare *session* stmt) values)
                       (catch Exception ex
                         (ex-data ex))))))

    (is (instance? clojure.lang.ExceptionInfo
                   (async/<!! (execute-chan *session* stmt))))
    (is (instance? clojure.lang.ExceptionInfo
                   (async/<!! (execute-chan *session* "select * from users;"
                                            {:values ["foo"]}))))
    (is (instance? Exception
                   (async/<!! (execute-chan *session* "select * from users;"
                                            {:fetch-size :wtf}))))

    (is (instance? clojure.lang.ExceptionInfo
                   (async/<!! (execute-chan-buffered *session* stmt))))
    (is (instance? clojure.lang.ExceptionInfo
                   (async/<!! (execute-chan-buffered *session* "select * from users;"
                                            {:values ["foo"]}))))
    (is (instance? Exception
                   (async/<!! (execute-chan-buffered *session* "select * from users;"
                                            {:retry-policy :wtf}))))

    (is (instance? Exception
                   (try @(ma/execute *session* "select * from users;"
                                  {:values ["foo"]})
                     (catch Exception ex ex))))

    (is (instance? Exception
                   (try @(ma/execute *session* "select * from users;"
                                     {:fetch-size :wtf})
                        (catch Exception ex
                          ex))))

    (let [p (promise)]
      (execute-async *session* "select * from users;"
                     {:values  ["foo"]
                      :error (fn [r] (deliver p r))})
      (is (:query (ex-data @p))))

    (let [p (promise)]
      (execute-async *session* "select * from users;"
                          {:fetch-size :wtf
                           :error (fn [r] (deliver p r))})
      (instance? Exception @p))))

(deftest test-lazy-query
  (is (= 10 (count (take 10 (lazy-query *session*
                                        (h/select :items
                                                (h/limit 2)
                                                (h/where {:si (int 0)}))
                                        (fn [q coll]
                                          (merge q (h/where {:si (-> coll last :si inc)}))))))))

  (is (= 4 (count (take 10 (lazy-query *session*
                                       (h/select :items
                                               (h/limit 2)
                                               (h/where {:si (int 0)}))
                                       (fn [q coll]
                                         (when (< (-> coll last :si) 3)
                                           (merge q (h/where {:si (-> coll last :si inc)}))))))))))


(let [get-private-field
      (fn [instance field-name]
        (.get
         (doto (.getDeclaredField (class instance) field-name)
           (.setAccessible true))
         instance))

      result-set-fn-with-execution-infos
      (fn [rs]
        (vary-meta rs assoc
                   :execution-info (execution-info rs)))

      get-fetch-size
      (fn [rs]
        (-> rs meta :execution-info first
            (get-private-field "statement")
            .getFetchSize))]

  (deftest test-fetch-size
    (let [result-set (execute *session*
                              "select * from items;"
                              {:fetch-size 3
                               :result-set-fn result-set-fn-with-execution-infos})]
      (is (= 3 (get-fetch-size result-set)))))

  (deftest test-fetch-size-chan
    (let [result-ch (execute-chan *session*
                                       "select * from items;"
                                       {:fetch-size 5
                                        :result-set-fn result-set-fn-with-execution-infos})]
      (is (= 5 (get-fetch-size (async/<!! result-ch)))))))

(deftest test-execute-chan-buffered
  (let [ch (execute-chan-buffered *session* "select * from items;" {:fetch-size 5})]
    (is (= 10 (count (loop [coll []]
                       (if-let [row (async/<!! ch)]
                         (recur (cons row coll))
                         coll))))))
  (let [ch (execute-chan-buffered *session* "select * from items;")]
    (is (= 10 (count (loop [coll []]
                       (if-let [row (async/<!! ch)]
                         (recur (cons row coll))
                         coll))))))

  (let [ch (execute-chan-buffered *session* "select * from items;" {:channel (async/chan 5)})]
    (is (= 10 (count (loop [coll []]
                       (if-let [row (async/<!! ch)]
                         (recur (cons row coll))
                         coll))))))

  (let [ch (execute-chan-buffered *session* "select * from items;" {:fetch-size 1})]
    (is (= 1 (count (loop [coll []]
                      (if-let [row (async/<!! ch)]
                        (do
                          (async/close! ch)
                          (recur (cons row coll)))
                        coll)))))))

(deftest test-named-bindings
  (let [prep-write (prepare *session* "INSERT INTO simple (id, text) VALUES(:id, :text);")
        prep-read (prepare *session* "SELECT * FROM simple WHERE id = :id;")
        an-id (int 100)]

    (is (= []
           (execute *session* prep-write {:values {:id an-id
                                                   :text "inserted via named bindings"}})))

    (is (= [{:id an-id
             :text "inserted via named bindings"}]
           (execute *session* prep-read {:values {:id an-id}})))

    (is (= [{:id an-id
             :text "inserted via named bindings"}]
           (execute *session* "SELECT * FROM simple WHERE id = :id;"
                    {:values {:id an-id}})))))

(deftest test-udt-encoder
  (let [encoder (udt-encoder *session* :udt)
        encoder-ct (udt-encoder *session* :udtct)
        tup (tuple-encoder *session* :users :tup)]
    (is (instance? UDTValue (encoder {:foo "f" "bar" 100})))
    (is (instance? UDTValue (encoder {:foo nil "bar" 100})))
    (is (instance? UDTValue (encoder {:foo nil "bar" 100})))
    (is (instance? UDTValue (encoder-ct {:foo "f" :tup (tup ["a" "b"])})))
    (is (= :qbits.alia.udt/type-not-found
           (-> (try (udt-encoder *session* :invalid-type) (catch Exception e e))
               ex-data
               :type)))
    (is (= :qbits.alia.tuple/type-not-found
           (-> (try (tuple-encoder *session* :users :invalid-type) (catch Exception e e))
               ex-data
               :type)))
    (is (= :qbits.alia.tuple/type-not-found
           (-> (try (tuple-encoder *session* :invalid-col :invalid-type) (catch Exception e e))
               ex-data
               :type)))))

(deftest test-result-set-types
  (is (instance? clojure.lang.LazySeq (execute *session* "select * from items;")))
  (is (instance? clojure.lang.PersistentVector (execute *session* "select * from items;"
                                                        {:result-set-fn #(into [] %)}))))

(defrecord Foo [foo bar])
(deftest test-udt-registry
  (let [codec qbits.alia.codec.udt-aware/codec]
    (qbits.alia.codec.udt-aware/register-udt! codec *session* :udt Foo)
    (is
     (= Foo
        (-> (execute *session* "select * from users limit 1"
                     {:codec codec})
            first
            :udt
            type)))
    (qbits.alia.codec.udt-aware/deregister-udt! codec *session* :udt Foo)))

(deftest test-custom-codec
  (is (-> (execute *session* "select * from users limit 1"
                   {:codec {:decoder (constantly 42)
                            :encoder identity}})
          first
          :valid
          (= 42)))
    ;; todo test encoder
  )
