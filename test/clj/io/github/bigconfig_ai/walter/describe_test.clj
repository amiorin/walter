(ns io.github.bigconfig-ai.walter.describe-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.walter.describe :as describe]))

(def base-opts
  {:profile "walter-test"
   :workdir ".green"
   :provider-compute "no-infra"
   :provider-backend "local"
   :ip "203.0.113.10"
   :user "ubuntu"
   :sudoer "root"
   :compute-pubkey "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY test@example.com"
   :walter {:tailnet "example-tailnet.ts.net"
            :users [{:name "ubuntu" :uid "1000" :remove false}]
            :repos [{:user "ubuntu" :org "acme" :repo "one" :branch "main" :worktrees []}
                    {:user "ubuntu" :org "acme" :repo "two" :branch "main" :worktrees []}]
            :packages [["ripgrep" "rg"] ["fish" "fish"] ["just" "just"]]}})

(defn- identity-opts [opts] opts)

(deftest describe-report-summarizes-a-reachable-workstation
  (let [calls (atom [])
        run-fn (fn [args opts]
                 (swap! calls conj [args opts])
                 {:ok? true :exit 0 :out "" :err ""})
        result (describe/describe-report base-opts run-fn identity-opts)]
    (is (= "walter-test" (:profile result)))
    (is (= {:compute "no-infra" :backend "local"} (:providers result)))
    (is (= :running (get-in result [:compute :status])))
    (is (= "ssh ok" (get-in result [:compute :detail])))
    (is (= "203.0.113.10" (get-in result [:compute :ip])))
    (testing "the workstation summary comes from desired state"
      (is (= ["203.0.113.10"] (get-in result [:workstation :hosts])))
      (is (= "root" (get-in result [:workstation :sudoer])))
      (is (= "example-tailnet.ts.net" (get-in result [:workstation :tailnet])))
      (is (= ["ubuntu"] (get-in result [:workstation :users])))
      (is (= 2 (get-in result [:workstation :repo-count])))
      (is (= 3 (get-in result [:workstation :package-count]))))
    (is (= "ssh" (first (ffirst @calls))))))

(deftest describe-report-classifies-a-failed-ssh-probe-as-unreachable
  (let [run-fn (fn [_args _opts]
                 {:ok? false :exit 255 :out "" :err "connection refused"})
        result (describe/describe-report base-opts run-fn identity-opts)]
    (is (= :unreachable (get-in result [:compute :status])))
    (is (str/includes? (get-in result [:compute :detail]) "connection refused"))))

(deftest describe-report-classifies-missing-compute-outputs-as-absent
  (testing "a cloud provider with no address was never applied"
    (let [result (describe/describe-report
                  (assoc base-opts :provider-compute "oci" :ip nil)
                  (fn [_ _] {:ok? true :exit 0 :out "" :err ""})
                  identity-opts)]
      (is (= :absent (get-in result [:compute :status])))
      (is (str/includes? (get-in result [:compute :detail])
                         ".green/walter-test/tofu-compute"))))

  (testing "no-infra is never absent — desired state supplies the host"
    (let [result (describe/describe-report
                  (assoc base-opts :ip nil :no-infra-compute-ip nil)
                  (fn [_ _] {:ok? true :exit 0 :out "" :err ""})
                  identity-opts)]
      (is (= :unreachable (get-in result [:compute :status])))
      (is (= "no host configured" (get-in result [:compute :detail]))))))

(deftest describe-report-falls-back-when-params-cannot-be-resolved
  (let [result (describe/describe-report
                base-opts
                (fn [_ _] {:ok? true :exit 0 :out "" :err ""})
                (fn [_] (throw (ex-info "no state" {}))))]
    (is (= :running (get-in result [:compute :status])))
    (is (str/includes? (get-in result [:compute :detail])
                       "could not resolve OpenTofu parameters"))))

(deftest describe-exits-non-zero-unless-compute-is-running
  (testing "running succeeds"
    (with-redefs [describe/describe-report
                  (constantly {:profile "t" :providers {} :workstation {}
                               :compute {:status :running :detail "ssh ok"}})]
      (let [r (atom nil)]
        (with-out-str (reset! r (describe/describe {})))
        (is (= 0 (:green/exit @r)))
        (is (= "t" (get-in @r [::describe/result :profile]))))))

  (testing "anything else fails with the reason"
    (with-redefs [describe/describe-report
                  (constantly {:profile "t" :providers {} :workstation {}
                               :compute {:status :absent :detail "no state"}})]
      (let [r (atom nil)]
        (with-out-str (reset! r (describe/describe {})))
        (is (= 1 (:green/exit @r)))
        (is (= "compute is absent — no state" (:green/err @r)))))))
