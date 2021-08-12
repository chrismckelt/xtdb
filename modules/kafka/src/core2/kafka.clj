(ns core2.kafka
  (:require [clojure.java.io :as io]
            [core2.api :as c2]
            [core2.log :as log]
            [core2.util :as util]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [clojure.spec.alpha :as s])
  (:import core2.log.Log
           java.io.Closeable
           java.nio.file.Path
           java.time.Duration
           [java.util Date Map Properties]
           [java.util.concurrent CompletableFuture ExecutionException]
           [org.apache.kafka.clients.admin AdminClient NewTopic TopicDescription]
           [org.apache.kafka.clients.consumer ConsumerRecord KafkaConsumer]
           [org.apache.kafka.clients.producer Callback KafkaProducer ProducerRecord]
           [org.apache.kafka.common.errors InterruptException TopicExistsException UnknownTopicOrPartitionException]
           org.apache.kafka.common.TopicPartition))

(defn ->kafka-config [{:keys [bootstrap-servers ^Path properties-file properties-map]}]
  (merge {"bootstrap.servers" bootstrap-servers}
         (when properties-file
           (with-open [in (io/reader (.toFile properties-file))]
             (->> (doto (Properties.)
                    (.load in))
                  (into {}))))
         properties-map))

(defn ->topic-opts [opts]
  (-> opts
      (update :topic-config (fn [config]
                              (merge {"message.timestamp.type" "LogAppendTime"}
                                     config)))))

(deftype KafkaLog [^KafkaProducer producer
                   ^KafkaConsumer consumer
                   ^TopicPartition tp
                   ^Duration poll-duration]
  Log
  (appendRecord [_ record]
    (let [fut (CompletableFuture.)]
      (.send producer (ProducerRecord. (.topic tp) nil record)
             (reify Callback
               (onCompletion [_ record-metadata e]
                 (if e
                   (.completeExceptionally fut e)
                   (.complete fut (log/->LogRecord (c2/->TransactionInstant (.offset record-metadata)
                                                                            (Date. (.timestamp record-metadata)))
                                                   record))))))
      fut))

  (readRecords [_ after-offset limit]
    (if after-offset
      (.seek consumer tp (inc ^long after-offset))
      (.seekToBeginning consumer [tp]))

    (try
      (->> (for [^ConsumerRecord record (.poll consumer poll-duration)]
             (log/->LogRecord (c2/->TransactionInstant (.offset record) (Date. (.timestamp record)))
                              (.value record)))
           (into [] (take limit)))
      (catch InterruptException e
        (throw (.getCause e)))))

  Closeable
  (close [_]
    (util/try-close consumer)
    (util/try-close producer)))

(defn ->producer [kafka-config]
  (KafkaProducer. ^Map (merge {"enable.idempotence" "true"
                               "acks" "all"
                               "compression.type" "snappy"
                               "key.serializer" "org.apache.kafka.common.serialization.ByteBufferSerializer"
                               "value.serializer" "org.apache.kafka.common.serialization.ByteBufferSerializer"}
                              kafka-config)))

(defn ->consumer ^org.apache.kafka.clients.consumer.KafkaConsumer [kafka-config]
  (KafkaConsumer. ^Map (merge {"enable.auto.commit" "false"
                               "isolation.level" "read_committed"
                               "auto.offset.reset" "earliest"
                               "key.deserializer" "org.apache.kafka.common.serialization.ByteBufferDeserializer"
                               "value.deserializer" "org.apache.kafka.common.serialization.ByteBufferDeserializer"}
                              kafka-config)))

(defn ensure-topic-exists [kafka-config {:keys [topic-name replication-factor create-topic? topic-config]}]
  (with-open [admin-client (AdminClient/create ^Map kafka-config)]
    (or (when-let [^TopicDescription
                   desc (-> (try
                              @(.all (.describeTopics admin-client [topic-name]))
                              (catch ExecutionException e
                                (let [e (.getCause e)]
                                  (when-not (instance? UnknownTopicOrPartitionException e)
                                    (throw e)))))
                            (get topic-name))]
          (let [partition-count (count (.partitions desc))]
            (when-not (= 1 partition-count)
              (throw (IllegalStateException. (format "'%s' topic has %d partitions, required 1"
                                                     topic-name partition-count))))
            :exists))

        (when create-topic?
          (let [new-topic (doto (NewTopic. ^String topic-name
                                           1 ; num-partitions
                                           ^short (short replication-factor))
                            (.configs topic-config))]
            (try
              @(.all (.createTopics admin-client [new-topic]))
              :created
              (catch ExecutionException e
                (let [cause (.getCause e)]
                  (when-not (instance? TopicExistsException cause)
                    (throw e)))))))

        (throw (IllegalStateException. (format "Topic '%s' does not exist", topic-name))))))

(s/def ::bootstrap-servers string?)
(s/def ::properties-file ::util/path)
(s/def ::properties-map ::util/string-map)
(s/def ::topic-name string?)
(s/def ::replication-factor pos-int?)
(s/def ::create-topic? boolean?)
(s/def ::topic-config ::util/string-map)

(s/def ::poll-duration ::util/duration)

(defmethod ig/prep-key ::log [_ opts]
  (-> (merge {:bootstrap-servers "localhost:9092"
              :replication-factor 1
              :create-topic? true
              :poll-duration "PT1S"}
             opts)
      (util/maybe-update :properties-file util/->path)
      (util/maybe-update :poll-duration util/->duration)))

(defmethod ig/pre-init-spec ::log [_]
  (s/keys :req-un [::bootstrap-servers ::topic-name ::create-topic? ::replication-factor ::poll-duration]
          :opt-un [::properties-file ::properties-map ::topic-config]))

(defmethod ig/init-key ::log [_ {:keys [topic-name poll-duration] :as kafka-opts}]
  (let [kafka-config (->kafka-config kafka-opts)
        tp (TopicPartition. topic-name 0)]
    (ensure-topic-exists kafka-config kafka-opts)
    (KafkaLog. (->producer kafka-config)
               (doto (->consumer kafka-config)
                 (.assign #{tp}))
               tp
               poll-duration)))

(defmethod ig/halt-key! ::log [_ log]
  (util/try-close log))
