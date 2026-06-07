(ns io.github.bigconfig-ai.walter.describe
  "Describe the active Walter workstation profile after provisioning."
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.render :as render]
   [big-config.workflow :as workflow]
   [clojure.string :as str]
   [io.github.bigconfig-ai.walter.ansible :as ansible]
   [io.github.bigconfig-ai.walter.params :as params]))

(def ^:private run-timeout-ms 30000)
(def ^:private ssh-probe-timeout-ms 10000)

(defn- run
  ([args] (run args {}))
  ([args {:keys [timeout-ms extra-env]
          :or   {timeout-ms run-timeout-ms}}]
   (try
     (let [proc   (p/process args (cond-> {:in  (java.io.ByteArrayInputStream. (byte-array 0))
                                           :out :string
                                           :err :string}
                                    (seq extra-env) (assoc :extra-env extra-env)))
           result (deref proc timeout-ms ::timeout)]
       (if (= ::timeout result)
         (do
           (p/destroy-tree proc)
           {:ok? false :exit -1 :out ""
            :err (format "command timed out after %dms" timeout-ms)})
         (let [{:keys [exit out err]} result]
           {:ok? (zero? exit) :exit exit :out out :err err})))
     (catch Exception e
       {:ok? false :exit -1 :out "" :err (.getMessage e)}))))

(defn- trim-snippet [s]
  (let [s (some-> s str/trim)]
    (when-not (str/blank? s)
      (if (> (count s) 200) (str (subs s 0 200) "…") s))))

(defn- result-detail
  [label {:keys [exit out err]}]
  (let [snippet (or (trim-snippet err) (trim-snippet out))]
    (format "%s failed (exit %d)%s"
            label
            (or exit -1)
            (if snippet (str " — " snippet) ""))))

(defn- provider-summary
  [params]
  {:compute (:provider-compute params)
   :backend (:provider-backend params)})

(defn- compute-target
  [{:keys [provider-compute ip user sudoer no-infra-compute-ip
           no-infra-compute-user no-infra-compute-sudoer]}]
  (let [ip (if (and (= provider-compute "no-infra")
                    (or (str/blank? ip) (= "192.168.0.1" ip))
                    (not (str/blank? no-infra-compute-ip)))
             no-infra-compute-ip
             ip)]
    {:ip ip
     :user (or (not-empty user)
               (not-empty no-infra-compute-user)
               (not-empty sudoer)
               (not-empty no-infra-compute-sudoer)
               "root")}))

(defn- ssh-base-args
  [{:keys [ip user]}]
  ["ssh"
   "-o" "BatchMode=yes"
   "-o" "ConnectTimeout=5"
   "-o" "StrictHostKeyChecking=accept-new"
   (str user "@" ip)])

(defn- ssh-run
  [run-fn compute remote-args]
  (run-fn (into (ssh-base-args compute) remote-args)
          {:timeout-ms ssh-probe-timeout-ms}))

(defn- compute-status
  [run-fn params]
  (let [{:keys [ip] :as target} (compute-target params)]
    (cond
      (str/blank? ip)
      (assoc target :running? false :detail "missing IP address")

      :else
      (let [{:keys [ok?] :as result} (ssh-run run-fn target ["true"])]
        (assoc target
               :running? (boolean ok?)
               :detail (if ok?
                         "ssh ok"
                         (str (result-detail "ssh" result)
                              (when (= "192.168.0.1" ip)
                                "; no Tofu output found or host is down"))))))))

(defn- resolve-walter-opts
  [opts walter-opts-fn]
  (try
    {:opts (walter-opts-fn opts)}
    (catch Exception e
      {:opts opts
       :detail (str "could not resolve OpenTofu parameters: " (.getMessage e))})))

(defn- workstation-summary
  [params]
  (let [{:keys [hosts sudoer users repos packages]} (ansible/data-fn params nil)]
    {:hosts (vec hosts)
     :sudoer sudoer
     :users (mapv :name users)
     :repo-count (count repos)
     :package-count (count packages)}))

(defn describe-report
  "Build a Walter describe report from `opts`."
  ([opts] (describe-report opts run params/walter-opts))
  ([opts run-fn walter-opts-fn]
   (let [{opts' :opts resolve-detail :detail} (resolve-walter-opts opts walter-opts-fn)
         profile (::render/profile opts')
         params' (::workflow/params opts')
         providers (provider-summary params')
         compute (cond-> (compute-status run-fn params')
                   resolve-detail (update :detail #(str % "; " resolve-detail)))
         workstation (workstation-summary params')]
     {:profile profile
      :providers providers
      :compute compute
      :workstation workstation
      :fatal-error? false})))

(defn- present
  [x]
  (if (str/blank? (str x)) "unknown" (str x)))

(defn- join-present
  [xs]
  (let [xs (remove str/blank? (map str xs))]
    (if (seq xs) (str/join ", " xs) "unknown")))

(defn- print-report
  [{:keys [profile providers compute workstation]}]
  (println (format "Profile: %s" (present profile)))
  (println)
  (println "Providers:")
  (println (format "  Compute: %s" (present (:compute providers))))
  (println (format "  Backend: %s" (present (:backend providers))))
  (println)
  (println "Compute:")
  (println (format "  IP: %s" (present (:ip compute))))
  (println (format "  SSH user: %s" (present (:user compute))))
  (println (format "  Status: %s%s"
                   (if (:running? compute) "running" "not reachable")
                   (if-let [detail (not-empty (:detail compute))]
                     (format " (%s)" detail)
                     "")))
  (println)
  (println "Workstation:")
  (println (format "  Hosts: %s" (join-present (:hosts workstation))))
  (println (format "  Sudoer: %s" (present (:sudoer workstation))))
  (println (format "  Users: %s" (join-present (:users workstation))))
  (println (format "  Repositories: %d" (:repo-count workstation)))
  (println (format "  Packages: %d" (:package-count workstation))))

(defn describe
  "big-config workflow step for `bb run package describe`."
  [_step-fns opts]
  (let [result (describe-report opts)]
    (print-report result)
    (merge opts
           {::result result}
           (if (:fatal-error? result)
             {::bc/exit 1
              ::bc/err "describe failed"}
             (core/ok)))))
