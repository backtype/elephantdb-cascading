(ns elephantdb.cascading.integration-test
  (:import [cascading.operation Identity])
  (:import [cascading.pipe Each GroupBy Pipe SubAssembly])
  (:import [cascading.operation Debug])
  (:import [cascading.tuple Fields Tuple TupleEntry])
  (:import [cascading.flow FlowConnector])
  (:import [cascading.tap Hfs])
  (:import [elephantdb.persistence JavaBerkDB LocalPersistenceFactory])
  (:import [elephantdb DomainSpec Utils])
  (:import [elephantdb.hadoop ReplaceUpdater])
  (:import [elephantdb.cascading ElephantDBTap ElephantDBTap$Args ElephantTailAssembly])
  (:import [org.apache.hadoop.io BytesWritable IntWritable])
  (:import [org.apache.hadoop.mapred JobConf])
  (:use [elephantdb testing])
  (:use [clojure test])
  )

(defn create-source [tmppath pairs]
  (let [source (Hfs. (Fields. (into-array ["key" "value"])) tmppath)
        coll (.openForWrite source (JobConf.))]
    (doseq [[k v] pairs]
      (.add coll (Tuple. (into-array Object [(BytesWritable. k) (BytesWritable. v)])))
      )
    (.close coll)
    source ))

(defn emit-to-sink [sink pairs]
  (with-fs-tmp [fs tmp]
    (let [source (create-source tmp pairs)
          p (Pipe. "pipe")
          p (ElephantTailAssembly. p sink)
          flow (.connect (FlowConnector.) source sink p)]
      (.complete flow)
      )))

(defn mk-options [updater]
  (let [ret (ElephantDBTap$Args.)]
    (set! (. ret updater) updater)
    ret
    ))

(defn check-results [dpath pairs]
  (with-single-service-handler [handler {"domain" dpath}]
    (check-domain "domain" handler pairs)
    ))

(deffstest test-basic [fs tmp]
  (let [spec (DomainSpec. (JavaBerkDB.) 4)
        sink (ElephantDBTap. tmp spec (mk-options nil))
        data [[(barr 0) (barr 0 0)]
              [(barr 1) (barr 1 1)]
              [(barr 2) (barr 2 2)]
              [(barr 3) (barr 3 3)]
              [(barr 4) (barr 4 4)]
              [(barr 5) (barr 5 5)]
              [(barr 6) (barr 6 5)]
              [(barr 7) (barr 7 5)]
              [(barr 8) (barr 8 5)]
              ]
        data2 [[(barr 0) (barr 1)
                (barr 10) (barr 100)]]]
    (emit-to-sink sink data)
    (check-results tmp data)
    (emit-to-sink sink data2)
    (check-results tmp (conj data2 [(barr 1) nil]))
    ))

(deffstest test-incremental [fs tmp]
  (let [spec (DomainSpec. (JavaBerkDB.) 2)
        sink (ElephantDBTap. tmp spec (mk-options (ReplaceUpdater.)))
        data [[(barr 0) (barr 0 0)]
              [(barr 1) (barr 1 1)]
              [(barr 2) (barr 2 2)]
              ]
        data2 [[(barr 0) (barr 1)]
               [(barr 3) (barr 3)]]
        data3 [[(barr 0) (barr 1)]
               [(barr 1) (barr 1 1)]
               [(barr 2) (barr 2 2)]
               [(barr 3) (barr 3)]]]
    (emit-to-sink sink data)
    (check-results tmp data)
    (emit-to-sink sink data2)
    (check-results tmp data3)
    ))

(defn get-tuples [sink]
  (with-open [it (.openForRead sink (JobConf.))]
    (doall
     (map
      #(vec (seq (.getTuple %)))
      (iterator-seq it)))))

(defn read-etap-with-flow [path]
  (with-fs-tmp [fs tmp]
    (let [source (ElephantDBTap. path)
          sink (Hfs. (Fields. (into-array ["key" "value"])) tmp)
          p (Pipe. "flow")
          flow (.connect (FlowConnector.) source sink p)]
      (.complete flow)
      (for [[k v] (get-tuples sink)]
        [(Utils/getBytes k) (Utils/getBytes v)]
        ))))

(deffstest test-source [fs tmp]
  (let [pairs [[(barr 0) (barr 0 2)]
               [(barr 1) (barr 1 1)]
               [(barr 2) (barr 9 1)]
               [(barr 33) (barr 0 2 3)]
               [(barr 4) (barr 0)]
               [(barr 5) (barr 1)]
               [(barr 6) (barr 3)]
               [(barr 7) (barr 9 101 9 9)]
               [(barr 81) (barr 9 9 9 1)]
               [(barr 9) (barr 9 9 2)]
               [(barr 102) (barr 3 6)]
               ]]
    (with-sharded-domain [dpath
                          {:num-shards 6
                           :persistence-factory (JavaBerkDB.)}
                          pairs]
      (is (kv-pairs= pairs (read-etap-with-flow dpath)))
      )))
