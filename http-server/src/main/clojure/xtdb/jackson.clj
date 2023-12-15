(ns xtdb.jackson
  (:require [jsonista.core :as json])
  (:import (com.fasterxml.jackson.databind ObjectMapper)
           (com.fasterxml.jackson.datatype.jsr310 JavaTimeModule)
           (com.fasterxml.jackson.databind.module SimpleModule)
           (xtdb.jackson JsonLdModule OpsDeserializer PutDeserializer DeleteDeserializer EraseDeserializer
                         TxDeserializer CallDeserializer)
           (xtdb.api TransactionKey)
           (xtdb.tx Ops Put Delete Erase Tx Call)
           (xtdb.query Query Query$From Query$Where Query$Limit Query$Offset Query$OrderBy
                       Query$QueryTail Query$Unify Query$UnifyClause Query$Pipeline Query$Return
                       Query$With Query$WithCols Query$Without Query$UnnestCol Query$UnnestVar Expr
                       VarSpec ColSpec Basis QueryRequest
                       Query$Aggregate Query$Relation Query$IJoin
                       QueryDeserializer FromDeserializer WhereDeserializer
                       LimitDeserializer OffsetDeserializer OrderByDeserializer
                       UnnestColDeserializer ReturnDeserializer QueryTailDeserializer
                       WithDeserializer WithColsDeserializer WithoutDeserializer UnnestVarDeserializer
                       UnifyDeserializer UnifyClauseDeserializer PipelineDeserializer TxKeyDeserializer
                       BasisDeserializer QueryRequestDeserializer ExprDeserializer
                       AggregateDeserializer RelDeserializer IJoinDeserializer
                       VarSpecDeserializer ColSpecDeserializer)))

#_
(defn decode-throwable [{:xtdb.error/keys [message class data] :as _err}]
  (case class
    "xtdb.IllegalArgumentException" (err/illegal-arg (:xtdb.error/error-key data) data)
    "xtdb.RuntimeException" (err/runtime-err (:xtdb.error/error-key data) data)
    (ex-info message data)))

(def ^com.fasterxml.jackson.databind.ObjectMapper json-ld-mapper
  (json/object-mapper {:encode-key-fn true
                       :decode-key-fn true
                       :modules [(JsonLdModule.)]}))

(def ^com.fasterxml.jackson.databind.ObjectMapper tx-op-mapper
  (doto (ObjectMapper.)
    (.registerModule (JavaTimeModule.))
    (.registerModule (JsonLdModule.))
    (.registerModule (doto (SimpleModule. "xtdb.tx")
                       (.addDeserializer Ops (OpsDeserializer.))
                       (.addDeserializer Put (PutDeserializer.))
                       (.addDeserializer Delete (DeleteDeserializer.))
                       (.addDeserializer Erase (EraseDeserializer.))
                       (.addDeserializer Call (CallDeserializer.))
                       (.addDeserializer Tx (TxDeserializer.))))))

(def ^com.fasterxml.jackson.databind.ObjectMapper query-mapper
  (doto (ObjectMapper.)
    (.registerModule (JavaTimeModule.))
    (.registerModule (JsonLdModule.))
    (.registerModule (doto (SimpleModule. "xtdb.query")
                       (.addDeserializer QueryRequest (QueryRequestDeserializer.))
                       (.addDeserializer Query (QueryDeserializer.))
                       (.addDeserializer Query$QueryTail (QueryTailDeserializer.))
                       (.addDeserializer Query$Unify (UnifyDeserializer.))
                       (.addDeserializer Query$UnifyClause (UnifyClauseDeserializer.))
                       (.addDeserializer Query$Pipeline (PipelineDeserializer.))
                       (.addDeserializer Query$From (FromDeserializer.))
                       (.addDeserializer Query$Where (WhereDeserializer.))
                       (.addDeserializer Query$Limit (LimitDeserializer.))
                       (.addDeserializer Query$Offset (OffsetDeserializer.))
                       (.addDeserializer Query$OrderBy (OrderByDeserializer.))
                       (.addDeserializer Query$Return (ReturnDeserializer.))
                       (.addDeserializer Query$UnnestCol (UnnestColDeserializer.))
                       (.addDeserializer Query$UnnestVar (UnnestVarDeserializer.))
                       (.addDeserializer Query$With (WithDeserializer.))
                       (.addDeserializer Query$WithCols (WithColsDeserializer.))
                       (.addDeserializer Query$Without (WithoutDeserializer.))
                       (.addDeserializer Query$IJoin (IJoinDeserializer.))
                       (.addDeserializer Query$Aggregate (AggregateDeserializer.))
                       (.addDeserializer Query$Relation (RelDeserializer.))
                       (.addDeserializer VarSpec (VarSpecDeserializer.))
                       (.addDeserializer ColSpec (ColSpecDeserializer.))
                       (.addDeserializer TransactionKey (TxKeyDeserializer.))
                       (.addDeserializer Basis (BasisDeserializer.))
                       (.addDeserializer Expr (ExprDeserializer.))))))
