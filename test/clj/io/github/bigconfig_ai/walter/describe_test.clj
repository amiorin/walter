(ns io.github.bigconfig-ai.walter.describe-test
  (:require
   [big-config :as bc]
   [big-config.render :as render]
   [big-config.workflow :as workflow]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.walter.describe :as describe]))

(def base-opts
  {::render/profile "walter-test"
   ::workflow/params {:provider-compute "no-infra"
                      :provider-backend "local"
                      :package "walter"
                      :ip "203.0.113.10"
                      :user "ubuntu"
                      :sudoer "root"
                      :compute-pubkey "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com"}})

(defn- identity-opts [opts] opts)

(deftest describe-report-summarizes-reachable-workstation
  (let [calls (atom [])
        run-fn (fn [args opts]
                 (swap! calls conj [args opts])
                 {:ok? true :exit 0 :out "" :err ""})
        result (describe/describe-report base-opts run-fn identity-opts)]
    (is (= "walter-test" (:profile result)))
    (is (= {:compute "no-infra" :backend "local"} (:providers result)))
    (is (true? (get-in result [:compute :running?])))
    (is (= "ssh ok" (get-in result [:compute :detail])))
    (is (= ["203.0.113.10"] (get-in result [:workstation :hosts])))
    (is (= "root" (get-in result [:workstation :sudoer])))
    (is (pos? (get-in result [:workstation :repo-count])))
    (is (pos? (get-in result [:workstation :package-count])))
    (is (= "ssh" (first (ffirst @calls))))))

(deftest describe-report-soft-fails-unreachable-ssh
  (let [run-fn (fn [_args _opts]
                 {:ok? false :exit 255 :out "" :err "connection refused"})
        result (describe/describe-report base-opts run-fn identity-opts)]
    (is (false? (get-in result [:compute :running?])))
    (is (str/includes? (get-in result [:compute :detail]) "connection refused"))
    (is (false? (:fatal-error? result)))))

(deftest describe-workflow-step-sets-exit-status
  (with-redefs [describe/describe-report (constantly {:profile "test"
                                                      :providers {}
                                                      :compute {}
                                                      :workstation {}
                                                      :fatal-error? false})]
    (let [result (atom nil)]
      (with-out-str
        (reset! result (describe/describe [] {})))
      (is (= 0 (::bc/exit @result)))
      (is (= "test" (get-in @result [::describe/result :profile]))))))
