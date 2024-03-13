(ns xtdb.vector.writer
  (:require [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr])
  (:import (clojure.lang Keyword)
           (java.math BigDecimal)
           java.net.URI
           (java.nio ByteBuffer)
           (java.time Duration Instant LocalDate LocalDateTime LocalTime OffsetDateTime ZonedDateTime)
           (java.util Date List Map Set UUID)
           (org.apache.arrow.memory BufferAllocator)
           (org.apache.arrow.vector PeriodDuration ValueVector VectorSchemaRoot)
           (org.apache.arrow.vector.types.pojo Field FieldType)
           xtdb.Types
           (xtdb.types ClojureForm IntervalDayTime IntervalMonthDayNano IntervalYearMonth)
           (xtdb.vector FieldVectorWriters IRelationWriter IRowCopier IVectorReader IVectorWriter RelationReader RelationWriter RootWriter)))

(set! *unchecked-math* :warn-on-boxed)

(defn ->writer ^xtdb.vector.IVectorWriter [arrow-vec]
  (FieldVectorWriters/writerFor arrow-vec))

(defn value->arrow-type [v] (Types/valueToArrowType v))

(defprotocol ArrowWriteable
  (value->col-type [v]))

(defn write-value! [v ^xtdb.vector.IVectorWriter writer]
  (.writeObject writer v))

(extend-protocol ArrowWriteable
  nil
  (value->col-type [_] :null)

  Boolean
  (value->col-type [_] :bool)

  Byte
  (value->col-type [_] :i8)

  Short
  (value->col-type [_] :i16)

  Integer
  (value->col-type [_] :i32)

  Long
  (value->col-type [_] :i64)

  Float
  (value->col-type [_] :f32)

  Double
  (value->col-type [_] :f64)

  BigDecimal
  (value->col-type [_] :decimal))

(extend-protocol ArrowWriteable
  Date
  (value->col-type [_] [:timestamp-tz :micro "UTC"])

  Instant
  (value->col-type [_] [:timestamp-tz :micro "UTC"])

  ZonedDateTime
  (value->col-type [v] [:timestamp-tz :micro (.getId (.getZone v))])

  OffsetDateTime
  (value->col-type [v] [:timestamp-tz :micro (.getId (.getOffset v))])

  LocalDateTime
  (value->col-type [_] [:timestamp-local :micro])

  Duration
  (value->col-type [_] [:duration :micro])

  LocalDate
  (value->col-type [_] [:date :day])

  LocalTime
  (value->col-type [_] [:time-local :nano])

  IntervalYearMonth
  (value->col-type [_] [:interval :year-month])

  IntervalDayTime
  (value->col-type [_] [:interval :day-time])

  IntervalMonthDayNano
  (value->col-type [_] [:interval :month-day-nano])

  PeriodDuration
  (value->col-type [_] [:interval :month-day-nano]))

(extend-protocol ArrowWriteable
  (Class/forName "[B")
  (value->col-type [_] :varbinary)

  ByteBuffer
  (value->col-type [_] :varbinary)

  CharSequence
  (value->col-type [_] :utf8))

(extend-protocol ArrowWriteable
  List
  (value->col-type [v] [:list (apply types/merge-col-types (into #{} (map value->col-type) v))])

  Set
  (value->col-type [v] [:set (apply types/merge-col-types (into #{} (map value->col-type) v))])

  Map
  (value->col-type [v]
    (if (or (every? keyword? (keys v)) (every? string? (keys v)))
      [:struct (->> v
                    (into {} (map (juxt (comp symbol key)
                                        (comp value->col-type val)))))]

      #_[:map
         (apply types/merge-col-types (into #{} (map (comp value->col-type key)) v))
         (apply types/merge-col-types (into #{} (map (comp value->col-type val)) v))]
      (throw (UnsupportedOperationException. "Arrow Maps currently not supported")))))

(extend-protocol ArrowWriteable
  Keyword
  (value->col-type [_] :keyword)

  UUID
  (value->col-type [_] :uuid)

  URI
  (value->col-type [_] :uri)

  ClojureForm
  (value->col-type [_] :transit)

  xtdb.RuntimeException
  (value->col-type [_] :transit)

  xtdb.IllegalArgumentException
  (value->col-type [_] :transit))

(defn write-vec! [^ValueVector v, vs]
  (.clear v)

  (let [writer (->writer v)]
    (doseq [v vs]
      (.writeObject writer v))

    (.syncValueCount writer)

    v))

(defn rows->fields [rows]
  (->> (for [col-name (into #{} (mapcat keys) rows)]
         [(symbol col-name) (->> rows
                                 (into #{} (map (fn [row]
                                                  (types/col-type->field (value->col-type (get row col-name))))))
                                 (apply types/merge-fields ))])
       (into {})))

(defn ->vec-writer ^xtdb.vector.IVectorWriter [^BufferAllocator allocator, ^String col-name, ^FieldType field-type]
  (->writer (.createNewSingleVector field-type col-name allocator nil)))

(defn ->rel-copier ^xtdb.vector.IRowCopier [^IRelationWriter rel-wtr, ^RelationReader in-rel]
  (let [wp (.writerPosition rel-wtr)
        copiers (vec (for [^IVectorReader in-vec in-rel]
                       (.rowCopier in-vec (.colWriter rel-wtr (.getName in-vec)))))]
    (reify IRowCopier
      (copyRow [_ src-idx]
        (.startRow rel-wtr)
        (let [pos (.getPosition wp)]
          (doseq [^IRowCopier copier copiers]
            (.copyRow copier src-idx))
          (.endRow rel-wtr)
          pos)))))

(defn ->rel-writer ^xtdb.vector.IRelationWriter [^BufferAllocator allocator]
  (RelationWriter. allocator))

(defn root->writer ^xtdb.vector.IRelationWriter [^VectorSchemaRoot root]
  (RootWriter. root))

(defn struct-writer->rel-copier ^xtdb.vector.IRowCopier [^IVectorWriter vec-wtr, ^RelationReader in-rel]
  (let [wp (.writerPosition vec-wtr)
        copiers (for [^IVectorReader src-vec in-rel]
                  (.rowCopier src-vec (.structKeyWriter vec-wtr (.getName src-vec))))]
    (reify IRowCopier
      (copyRow [_ src-idx]
        (let [pos (.getPosition wp)]
          (.startStruct vec-wtr)
          (doseq [^IRowCopier copier copiers]
            (.copyRow copier src-idx))
          (.endStruct vec-wtr)
          pos)))))

(defmulti open-vec (fn [_allocator col-name-or-field _vs]
                     (if (instance? Field col-name-or-field)
                       :field
                       :col-name)))

(alter-meta! #'open-vec assoc :tag ValueVector)

(defmethod open-vec :col-name [^BufferAllocator allocator, ^String col-name, vs]
  (util/with-close-on-catch [res (-> (FieldType/notNullable #xt.arrow/type :union)
                                     (.createNewSingleVector (str col-name) allocator nil))]
    (doto res (write-vec! vs))))

(defmethod open-vec :field [allocator ^Field field vs]
  (util/with-close-on-catch [res (.createVector field allocator)]
    (doto res (write-vec! vs))))

(defn open-rel ^xtdb.vector.RelationReader [vecs]
  (vr/rel-reader (map vr/vec->reader vecs)))

(defn open-params ^xtdb.vector.RelationReader [allocator params-map]
  (open-rel (for [[k v] params-map]
              (open-vec allocator k [v]))))

(def empty-params (vr/rel-reader [] 1))

(defn vec-wtr->rdr ^xtdb.vector.IVectorReader [^xtdb.vector.IVectorWriter w]
  (vr/vec->reader (.getVector (doto w (.syncValueCount)))))

(defn rel-wtr->rdr ^xtdb.vector.RelationReader [^xtdb.vector.IRelationWriter w]
  (vr/rel-reader (map vec-wtr->rdr (vals w))
                 (.getPosition (.writerPosition w))))

(defn append-vec [^IVectorWriter vec-writer, ^IVectorReader in-col]
  (let [row-copier (.rowCopier in-col vec-writer)]
    (dotimes [src-idx (.valueCount in-col)]
      (.copyRow row-copier src-idx))))

(defn append-rel [^IRelationWriter dest-rel, ^RelationReader src-rel]
  (doseq [^IVectorReader src-col src-rel]
    (append-vec (.colWriter dest-rel (.getName src-col)) src-col))

  (let [wp (.writerPosition dest-rel)]
    (.setPosition wp (+ (.getPosition wp) (.rowCount src-rel)))))
