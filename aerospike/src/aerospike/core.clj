(ns aerospike.core
  "Entry point for aerospike tests"
  (:require [aerospike [support :as support]
                       [counter :as counter]
                       [cas-register :as cas-register]
                       [nemesis :as nemesis]
                       [pause :as pause]
                       [set :as set]]
            [jepsen [cli :as cli]
                    [checker :as checker]
                    [generator :as gen]
                    [tests :as tests]]
            [jepsen.os.debian :as debian])
  (:gen-class))

(defn workloads
  "The workloads we can run. Each workload is a map like

      {:generator         a generator of client ops
       :final-generator   a generator to run after the cluster recovers
       :client            a client to execute those ops
       :checker           a checker
       :model             for the checker}

  Or, for some special cases where nemeses and workloads are coupled, we return
  a keyword here instead."
  []
  {:cas-register (cas-register/workload)
   :counter      (counter/workload)
   :set          (set/workload)
   :pause        :pause}) ; special case

(defn workload+nemesis
  "Finds the workload and nemesis for a given set of parsed CLI options."
  [opts]
  (case (:workload opts)
    :pause (pause/workload+nemesis opts)

    {:workload (get (workloads) (:workload opts))
     :nemesis  (nemesis/full opts)}))

(defn aerospike-test
  "Constructs a Jepsen test map from CLI options."
  [opts]
  (let [{:keys [workload nemesis]} (workload+nemesis opts)
        {:keys [generator
                final-generator
                client
                checker
                model]} workload
        time-limit (:time-limit opts)
        generator (->> generator
                       (gen/nemesis
                         (->> (:generator nemesis)
                              (gen/delay 1)))
                       (gen/time-limit (:time-limit opts)))
        generator (if-not (or final-generator (:final-generator nemesis))
                    generator
                    (gen/phases generator
                                (gen/log "Healing cluster")
                                (gen/nemesis (:final-generator nemesis))
                                (gen/log "Waiting for quiescence")
                                (gen/sleep 10)
                                (gen/clients final-generator)))]
    (merge tests/noop-test
           opts
           {:name     (str "aerospike " (name (:workload opts)))
            :os       debian/os
            :db       (support/db opts)
            :client   client
            :nemesis  (:nemesis nemesis)
            :generator generator
            :checker  (checker/compose
                      {:perf (checker/perf)
                       :workload checker})
            :model    model})))

(def opt-spec
  "Additional command-line options"
  [[nil "--workload WORKLOAD" "Test workload to run"
    :parse-fn keyword
    :missing (str "--workload " (cli/one-of (workloads)))
    :validate [(workloads) (cli/one-of (workloads))]]
   [nil "--replication-factor NUMBER" "Number of nodes which must store data"
    :parse-fn #(Long/parseLong %)
    :default 3
    :validate [pos? "must be positive"]]
   [nil "--max-dead-nodes NUMBER" "Number of nodes that can simultaneously fail"
    :parse-fn #(Long/parseLong %)
    :default  2
    :validate [(complement neg?) "must be non-negative"]]
   [nil "--clean-kill" "Terminate processes with SIGTERM to simulate fsync before commit"
    :default false]
   [nil "--no-clocks" "Allow the nemesis to change the clock"
    :default  false
    :assoc-fn (fn [m k v] (assoc m :clocks? (not v)))]
   [nil "--no-partitions" "Allow the nemesis to introduce partitions"
    :default  false
    :assoc-fn (fn [m k v] (assoc m :partitions? (not v)))]
   [nil "--no-kills" "Allow the nemesis to kill processes."
    :default  false
    :assoc-fn (fn [m k v] (assoc m :kills? (not v)))]
   [nil "--pause-mode MODE" "Whether to pause nodes by pausing the process, or slowing the network"
    :default :process
    :parse-fn keyword
    :validate [#{:process :net :clock} "Must be one of :clock, :process, :net."]]])

(defn -main
  "Handles command-line arguments, running a Jepsen command."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn   aerospike-test
                                         :opt-spec  opt-spec})
                   (cli/serve-cmd))
            args))
