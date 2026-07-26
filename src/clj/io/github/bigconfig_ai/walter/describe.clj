(ns io.github.bigconfig-ai.walter.describe
  "Describe the active Walter desired state after provisioning.

  The report shows the configured providers, compute status, and a summary of
  the workstation the Ansible stage would build. Compute is `absent` when
  OpenTofu holds no compute outputs, `unreachable` when it does but SSH fails,
  and `running` otherwise; anything but `running` marks the step failed."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [green.cli :as green-cli]
   [green.process :as process]
   [io.github.bigconfig-ai.walter.tools :as tools]))

;;; -------------------------------------------------------------- command helpers

(def ^:private run-timeout-ms 30000)
(def ^:private ssh-probe-timeout-ms 10000)

(defn- run
  ([args] (run args {}))
  ([args {:keys [timeout-ms]
          :or {timeout-ms run-timeout-ms}
          :as opts}]
   (process/run-with-timeout args (dissoc opts :timeout-ms) timeout-ms)))

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

(defn- ssh-base-args
  [{:keys [ip user]}]
  ["ssh"
   "-o" "BatchMode=yes"
   "-o" "ConnectTimeout=5"
   "-o" "StrictHostKeyChecking=accept-new"
   (str user "@" ip)])

(defn- ssh-run
  [run-fn compute remote-args timeout-ms]
  (run-fn (into (ssh-base-args compute) remote-args)
          {:timeout-ms timeout-ms}))

;;; -------------------------------------------------------------- providers + compute

(defn provider-summary
  "Return configured provider names from merged params."
  [params]
  {:compute (:provider-compute params)
   :backend (:provider-backend params)})

(def ^:private placeholder-ip
  "The address Once's fallback compute params render with; never a real host."
  "192.168.0.1")

(defn- compute-target
  [{:keys [provider-compute ip user sudoer no-infra-compute-ip
           no-infra-compute-user no-infra-compute-sudoer]}]
  (let [ip (if (and (= provider-compute "no-infra")
                    (or (str/blank? ip) (= placeholder-ip ip))
                    (not (str/blank? no-infra-compute-ip)))
             no-infra-compute-ip
             ip)]
    {:ip ip
     :user (or (not-empty user)
               (not-empty sudoer)
               (when (= provider-compute "no-infra")
                 (or (not-empty no-infra-compute-user)
                     (not-empty no-infra-compute-sudoer)))
               "root")}))

(defn- compute-status
  "Classify compute as :running, :unreachable, or :absent.

  An address can only reach the report through the tofu-compute outputs, so its
  absence means the stage was never applied — except under `no-infra`, where
  OpenTofu creates nothing and desired state supplies the host itself."
  [run-fn params compute-detail]
  (let [external? (= "no-infra" (:provider-compute params))
        {:keys [ip] :as target} (compute-target params)]
    (cond
      (or (str/blank? ip) (= placeholder-ip ip))
      (if external?
        (assoc target :status :unreachable :detail "no host configured")
        (assoc target :status :absent
               :detail (or compute-detail
                           (str "no OpenTofu state in "
                                (tools/tool-dir params "tofu-compute")))))

      :else
      (let [{:keys [ok?] :as result} (ssh-run run-fn target ["true"] ssh-probe-timeout-ms)]
        (assoc target
               :status (if ok? :running :unreachable)
               :detail (if ok? "ssh ok" (result-detail "ssh" result)))))))

;;; -------------------------------------------------------------- workstation

(defn workstation-summary
  "What the Ansible stage would build, read straight from desired state."
  [params]
  (let [{:keys [hosts sudoer users repos packages tailnet]} (tools/data-fn params nil)]
    {:hosts (vec hosts)
     :sudoer sudoer
     :tailnet tailnet
     :users (mapv :name (remove :remove users))
     :repo-count (count repos)
     :package-count (count packages)}))

;;; -------------------------------------------------------------- top-level

(defn- tofu-output-params
  [run-fn opts tool]
  (let [dir (tools/tool-dir opts tool)
        result (run-fn ["tofu" "output" "-json"]
                       {:dir dir
                        :extra-env (tools/backend-credential-env opts)
                        :timeout-ms run-timeout-ms})]
    (if (:ok? result)
      (try
        {:params (or (get-in (json/parse-string (:out result) keyword)
                             [:params :value])
                     {})}
        (catch Exception e
          {:detail (str tool " output was not valid JSON: " (.getMessage e))}))
      {:detail (result-detail (str "tofu output in " dir) result)})))

(defn- resolve-tofu-opts
  [opts run-fn]
  (let [compute (tofu-output-params run-fn opts "tofu-compute")]
    {:opts (merge opts (:params compute))
     :detail (:detail compute)
     :compute-detail (:detail compute)}))

(defn- report
  [opts run-fn {:keys [detail compute-detail]}]
  (let [compute (compute-status run-fn opts compute-detail)
        ;; an absent compute already carries its own explanation
        compute (cond-> compute
                  (and detail (not= :absent (:status compute)))
                  (update :detail #(str % "; " detail)))]
    {:profile (:profile opts)
     :providers (provider-summary opts)
     :compute compute
     :workstation (workstation-summary opts)
     :fatal-error? false}))

(defn describe-report
  "Build a Walter describe report from flat green `opts`.

  The default arity reads the compute values from OpenTofu state. The
  two-argument arity accepts an injected command runner and treats `opts` as
  already resolved, keeping report construction process-free in tests."
  ([opts]
   (let [{opts' :opts :as resolved} (resolve-tofu-opts opts run)]
     (report opts' run (dissoc resolved :opts))))
  ([opts run-fn]
   (report opts run-fn nil))
  ([opts run-fn opts-fn]
   (try
     (report (opts-fn opts) run-fn nil)
     (catch Exception e
       (let [detail (str "could not resolve OpenTofu parameters: " (.getMessage e))]
         (report opts run-fn {:detail detail :compute-detail detail}))))))

;;; -------------------------------------------------------------- reporting

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
                   (name (or (:status compute) :unknown))
                   (if-let [detail (not-empty (:detail compute))]
                     (format " (%s)" detail)
                     "")))
  (println)
  (println "Workstation:")
  (println (format "  Hosts: %s" (join-present (:hosts workstation))))
  (println (format "  Tailnet: %s" (present (:tailnet workstation))))
  (println (format "  Sudoer: %s" (present (:sudoer workstation))))
  (println (format "  Users: %s" (join-present (:users workstation))))
  (println (format "  Repositories: %d" (:repo-count workstation)))
  (println (format "  Packages: %d" (:package-count workstation))))

(defn- compute-error
  "The failure message for compute that is anything but running."
  [{:keys [status detail]}]
  (when-not (= :running status)
    (format "compute is %s%s"
            (name (or status :unknown))
            (if-let [detail (not-empty detail)] (str " — " detail) ""))))

(defn describe
  "Print the report and return green's Unix-style outcome map."
  [opts]
  (let [result (describe-report opts)]
    (print-report result)
    (merge opts
           {::result result}
           (if-let [err (compute-error (:compute result))]
             {:green/exit 1 :green/err err}
             {:green/exit 0}))))

(defn describe-file
  "Read a desired-state file, overlay `GREEN_PAR_*`, and describe the
  workstation it names. Describing reads OpenTofu state and the host rather
  than changing either, so it runs outside the workflow and needs no
  validation gate."
  [path]
  (try
    (let [file (io/file path)]
      (if-not (.exists file)
        {:green/exit 2 :green/err (str "desired state file not found: " file)}
        (-> (edn/read-string (slurp file))
            green-cli/read-pars
            describe)))
    (catch Throwable t
      {:green/exit 2 :green/err (or (ex-message t) (str (class t)))})))
