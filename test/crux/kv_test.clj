(ns crux.kv-test
  (:require [clojure.test :as t]
            [crux.fixtures :as f :refer [*kv*]]
            [crux.kv :as cr]))

(t/use-fixtures :each f/start-system)

(def test-eid 1)

(t/deftest test-can-get-at-now
  (cr/-put *kv* [[test-eid :foo "Bar4"]])
  (t/is (= "Bar4" (cr/-get-at *kv* test-eid :foo)))
  (cr/-put *kv* [[test-eid :foo "Bar5"]])
  (t/is (= "Bar5" (cr/-get-at *kv* test-eid :foo)))

  ;; Insert into past
  (cr/-put *kv* [[test-eid :foo "foo1"]] #inst "2000-02-02")
  (t/is (= "Bar5" (cr/-get-at *kv* test-eid :foo))))

(t/deftest test-can-get-at-now-for-old-entry
  (cr/-put *kv* [[test-eid :foo "Bar3"]] #inst "2010-02-02")
  (t/is (= "Bar3" (cr/-get-at *kv* test-eid :foo))))

(t/deftest test-can-get-at-t
  (cr/-put *kv* [[test-eid :foo "Bar3"]] #inst "1901-01-31")
  (t/is (= "Bar3" (cr/-get-at *kv* test-eid :foo #inst "1901-02-01")))

  (cr/-put *kv* [[test-eid :foo "Bar4"]] #inst "1901-02-02")
  (cr/-put *kv* [[test-eid :foo "Bar5"]] #inst "1901-02-03")
  (cr/-put *kv* [[test-eid :foo "Bar6"]] #inst "1901-02-04")

  (t/is (= "Bar3" (cr/-get-at *kv* test-eid :foo #inst "1901-02-01")))
  (t/is (= "Bar4" (cr/-get-at *kv* test-eid :foo #inst "1901-02-02")))
  (t/is (= "Bar6" (cr/-get-at *kv* test-eid :foo #inst "1901-02-05"))))

(t/deftest test-can-get-nil-before-range
  (cr/-put *kv* [[test-eid :foo "Bar3"]] #inst "1901-02-02")
  (cr/-put *kv* [[test-eid :foo "Bar4"]] #inst "1901-02-03")
  (t/is (not (cr/-get-at *kv* test-eid :foo #inst "1901-01-31"))))

(t/deftest test-can-get-nil-outside-of-range
  (cr/-put *kv* [[test-eid :foo "Bar3"]] #inst "1986-10-22")
  (cr/-put *kv* [[test-eid :tar "Bar4"]] #inst "1986-10-22")
  (t/is (not (cr/-get-at *kv* test-eid :tar #inst "1986-10-21"))))

(t/deftest test-entity-ids
  (let [eid (cr/next-entity-id *kv*)]
    (dotimes [n 1000]
      (cr/next-entity-id *kv*))

    (t/is (= (+ eid 1001) (cr/next-entity-id *kv*)))))

(t/deftest test-write-and-fetch-entity
  (let [person (first f/people)
        eid (first (vals (cr/-put *kv* [person] #inst "1986-10-22")))]
    (t/is (= (dissoc person :crux.kv/id)
             (dissoc (cr/entity *kv* eid) :crux.kv/id)))))

(t/deftest test-fetch-entity-at-t
  (let [person (first f/people)
        eid (first (vals (cr/-put *kv* [(assoc person :name "Fred")] #inst "1986-10-22")))]
    (cr/-put *kv* [(assoc person :name "Freda" :crux.kv/id eid)] #inst "1986-10-24")
    (t/is (= "Fred"
             (:name (cr/entity *kv* eid #inst "1986-10-23"))))
    (t/is (= "Freda"
             (:name (cr/entity *kv* eid))))))

(t/deftest test-invalid-attribute-exception
  (try
    (cr/-put *kv* [[test-eid :unknown-attribute "foo1"]] #inst "1986-10-22")
    (assert false "Exception expected")
    (catch IllegalArgumentException e
      (t/is (= "Unrecognised schema attribute: :unknown-attribute"
               (.getMessage e))))))

(t/deftest test-transact-schema-attribute
  (cr/transact-schema! *kv* {:crux.kv.attr/ident :new-ident
                             :crux.kv.attr/type :string})
  (cr/-put *kv* [[test-eid :new-ident "foo1"]])
  (t/is (= "foo1" (cr/-get-at *kv* test-eid :new-ident)))

  (let [aid (cr/transact-schema! *kv* {:crux.kv.attr/ident :new-ident2
                                       :crux.kv.attr/type :long})]
    (t/is (= :new-ident2 (:crux.kv.attr/ident (cr/attr-aid->schema *kv* aid)))))

  (cr/-put *kv* [[test-eid :new-ident2 1]])
  (t/is (= 1 (cr/-get-at *kv* test-eid :new-ident2)))

  ;; test insertion of invalid type and consequent exception
  )

(t/deftest test-retract-attribute
  (cr/-put *kv* [[test-eid :foo "foo1"]] #inst "1986-10-22")
  (cr/-put *kv* [[test-eid :foo nil]])
  (t/is (not (cr/-get-at *kv* test-eid :foo)))
  (t/is (= "foo1" (cr/-get-at *kv* test-eid :foo #inst "1986-10-22"))))

(t/deftest test-get-attributes
  (cr/transact-schema! *kv* {:crux.kv.attr/ident :foo/new-ident2
                             :crux.kv.attr/type :long})
  (t/is (= #{:age :foo :last-name :name :salary :sex :tar :foo/new-ident2}
           (set (keys (cr/attributes *kv*))))))

(t/deftest test-store-and-retrieve-meta
  (cr/store-meta *kv* :foo {:bar 2})
  (t/is (= {:bar 2} (cr/get-meta *kv* :foo))))
