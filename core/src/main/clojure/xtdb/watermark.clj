(ns xtdb.watermark
  (:require [clojure.tools.logging :as log]
            xtdb.api
            [xtdb.util :as util])
  (:import java.lang.AutoCloseable
           java.util.concurrent.atomic.AtomicInteger
           xtdb.api.TransactionKey))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveTableWatermark
  (^java.util.Map columnFields [])
  (^xtdb.vector.RelationReader liveRelation [])
  (^xtdb.trie.LiveHashTrie liveTrie [])
  (^void close []))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveIndexWatermark
  (^java.util.Map allColumnFields [])
  (^xtdb.watermark.ILiveTableWatermark liveTable [^String tableName])
  (^void close []))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IWatermark
  (^xtdb.api.TransactionKey txBasis [])
  (^xtdb.watermark.ILiveIndexWatermark liveIndex [])

  (^void retain [])
  (^void close []
   "releases a reference to the Watermark.
    if this was the last reference, close it."))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IWatermarkSource
  (^xtdb.watermark.IWatermark openWatermark [^xtdb.api.TransactionKey txKey]))

(deftype Watermark [^TransactionKey tx-key, ^ILiveIndexWatermark live-idx-wm, ^AtomicInteger ref-cnt]
  IWatermark
  (txBasis [_] tx-key)
  (liveIndex [_] live-idx-wm)

  (retain [this]
    (log/trace "retain wm" (hash this))
    (when (zero? (.getAndIncrement ref-cnt))
      (throw (IllegalStateException. "watermark closed"))))

  AutoCloseable
  (close [this]
    (log/trace "release wm" (hash this))

    (when (zero? (.decrementAndGet ref-cnt))
      (log/trace "close wm" (hash this))
      (util/try-close live-idx-wm))))

(defn ->wm ^xtdb.watermark.IWatermark [tx-key live-idx-wm]
  (Watermark. tx-key live-idx-wm (AtomicInteger. 1)))
