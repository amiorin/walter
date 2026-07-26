(ns io.github.bigconfig-ai.walter.workflow-test
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.workflow :as wf]
   [io.github.bigconfig-ai.walter.tools :as tools]
   [io.github.bigconfig-ai.walter.workflow :as sut]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "walter-workflow-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree!
  [path]
  (doseq [f (reverse (file-seq (io/file path)))]
    (io/delete-file f true)))

(def ^:private valid
  {:profile "test"
   :workdir ".green"
   :compute-pubkey "ssh-ed25519 AAAATEST me@example.com"
   :walter {:tailnet "example-tailnet.ts.net"
            :users [{:name "ubuntu" :doomemacs "abc123" :remove false}]
            :repos [{:user "ubuntu" :org "acme" :repo "one" :branch "main" :worktrees []}]
            :packages [["ripgrep" "rg"]]}
   :provider-compute "no-infra"
   :provider-backend "local"
   :compute-prevent-destroy true
   :no-infra-compute-ip "203.0.113.10"
   :no-infra-compute-user "ubuntu"
   :no-infra-compute-sudoer "root"
   :no-infra-compute-uid "1000"})

;;; ------------------------------------------------------------------ start

(deftest start-refuses-to-run-on-invalid-state
  (let [result (sut/start-step (assoc valid :green/event :build
                                      :provider-compute "nope") {})]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) "unsupported :provider-compute"))))

(deftest credentials-are-required-only-when-a-provider-will-be-reached
  (let [hcloud (assoc valid :provider-compute "hcloud"
                      :hcloud-name "w" :hcloud-image "ubuntu-24.04"
                      :hcloud-server-type "cx23" :hcloud-location "hel1"
                      :hcloud-ssh-keys "key")]
    (testing "create demands them"
      (let [result (sut/start-step (assoc hcloud :green/event :create) {})]
        (is (= 2 (:green/exit result)))
        (is (str/includes? (:green/err result) "GREEN_PAR_HCLOUD_TOKEN"))))

    (testing "build renders from desired state alone"
      (is (= 0 (:green/exit (sut/start-step (assoc hcloud :green/event :build) {})))))

    (testing "a dry run does not reach a provider either"
      (is (= 0 (:green/exit (sut/start-step (assoc hcloud
                                                   :green/event :create
                                                   :green/dry-run true)
                                            {})))))

    (testing "the environment supplies it"
      (is (= 0 (:green/exit (sut/start-step (assoc hcloud :green/event :create)
                                            {"GREEN_PAR_HCLOUD_TOKEN" "tok"})))))))

(deftest the-overlay-is-typed-by-the-value-it-replaces
  (let [result (sut/start-step (assoc valid :green/event :build)
                               {"GREEN_PAR_COMPUTE_PREVENT_DESTROY" "false"})]
    (is (false? (:compute-prevent-destroy result))
        "the string \"false\" would be truthy")))

(deftest compute-destruction-is-protected-by-default
  (testing "a real delete stops"
    (let [result (sut/start-step (assoc valid :green/event :delete) {})]
      (is (= 2 (:green/exit result)))
      (is (str/includes? (:green/err result)
                         "GREEN_PAR_COMPUTE_PREVENT_DESTROY=false"))))

  (testing "the environment authorizes it"
    (is (= 0 (:green/exit (sut/start-step (assoc valid :green/event :delete)
                                          {"GREEN_PAR_COMPUTE_PREVENT_DESTROY" "false"})))))

  (testing "a dry run is never blocked — it destroys nothing"
    (is (= 0 (:green/exit (sut/start-step (assoc valid
                                                 :green/event :delete
                                                 :green/dry-run true)
                                          {})))))

  (testing "the default holds even when desired state omits the key"
    (let [result (sut/start-step (assoc (dissoc valid :compute-prevent-destroy)
                                        :green/event :delete)
                                 {})]
      (is (= 2 (:green/exit result))))))

;;; ------------------------------------------------------------------ wiring

(defn- graph
  "The static successor graph wire-fn describes for an event. A step that has
  no place in this event's graph is absent rather than empty."
  [event]
  (into {}
        (keep (fn [step]
                (when-let [wired (try (sut/wire-fn step {:green/event event})
                                      (catch IllegalArgumentException _ nil))]
                  [step (vec (rest wired))])))
        (into [:walter/start] sut/side-effecting-steps)))

(deftest create-provisions-then-forks-into-the-two-ansible-stages
  (let [g (graph :create)]
    (is (= [:walter/tofu-compute] (:walter/start g)))
    (is (= [:walter/ansible-local :walter/ansible-remote] (:walter/tofu-compute g))
        "the two ansible stages have no dependency on each other")
    (is (= [] (:walter/ansible-local g)))
    (is (= [] (:walter/ansible-remote g)))
    (testing "cleanup belongs to delete only"
      (is (nil? (:walter/ansible-cleanup g))))))

(deftest delete-runs-the-stages-in-reverse
  (let [g (graph :delete)]
    (is (= [:walter/ansible-cleanup] (:walter/start g))
        "the managed SSH config goes before anything is destroyed")
    (is (= [:walter/tofu-compute] (:walter/ansible-cleanup g)))
    (is (= [] (:walter/tofu-compute g)))))

(deftest build-follows-the-create-graph
  (is (= (graph :create) (graph :build))))

(deftest every-side-effecting-step-is-skipped-by-dry-run
  (let [plan-ids (fn [step]
                   (set (map :id (wf/advice-plan sut/workflow step))))]
    (doseq [step sut/side-effecting-steps]
      (is (contains? (plan-ids step) :green.dry-run/skip)
          (str step " would run during a dry run")))
    (testing "start is not skipped — validation has to happen"
      (is (not (contains? (plan-ids :walter/start) :green.dry-run/skip))))))

;;; ------------------------------------------------------------------ backends

(defn- backend-json
  [dir]
  (json/parse-string (slurp (io/file dir "backend.tf.json"))))

(deftest backend-advice-writes-the-selected-backend
  (let [workdir (temp-dir)
        opts (assoc valid :workdir workdir)
        dir (tools/tool-dir opts "tofu-compute")
        advice (sut/backend-advice "tofu-compute")]
    (try
      (testing "local"
        (advice opts)
        (is (= {"terraform" {"backend" {"local" {}}}} (backend-json dir))))

      (testing "s3 keys state by profile and stage"
        (advice (assoc opts :provider-backend "s3"
                       :s3-bucket "tf-state" :s3-region "eu-west-1"))
        (is (= {"bucket" "tf-state"
                "key" "test/tofu-compute.tfstate"
                "region" "eu-west-1"}
               (get-in (backend-json dir) ["terraform" "backend" "s3"]))))

      (testing "r2 is s3-compatible, and names no credentials"
        (advice (assoc opts :provider-backend "r2"
                       :r2-bucket "tf-state"
                       :r2-endpoint "https://acct.r2.cloudflarestorage.com"
                       :r2-access-key-id "AKIA" :r2-secret-access-key "shhh"))
        (let [s3 (get-in (backend-json dir) ["terraform" "backend" "s3"])
              rendered (slurp (io/file dir "backend.tf.json"))]
          (is (= "auto" (get s3 "region")))
          (is (= {"s3" "https://acct.r2.cloudflarestorage.com"} (get s3 "endpoints")))
          (is (not (str/includes? rendered "AKIA")))
          (is (not (str/includes? rendered "shhh")))))

      (testing "an unknown backend is refused rather than rendered"
        (is (thrown? clojure.lang.ExceptionInfo
                     (advice (assoc opts :provider-backend "gcs")))))
      (finally (delete-tree! workdir)))))

;;; ------------------------------------------------------------------ a whole build

(def ^:private expected-build-artifacts
  ["tofu-compute/backend.tf.json"
   "tofu-compute/main.tf"
   "ansible-local/ansible.cfg"
   "ansible-local/inventory.ini"
   "ansible-local/main.yml"
   "ansible-remote/ansible.cfg"
   "ansible-remote/main.yml"
   "ansible-remote/inventory.json"
   "ansible-remote/default.config.yml"
   "ansible-remote/roles/root/tasks/main.yml"
   "ansible-remote/roles/users/files/xterm-ghostty"
   "ansible-remote/roles/users/tasks/packages.yml"
   "ansible-remote/roles/users/tasks/repos.yml"
   "ansible-remote/roles/users/tasks/ssh-config.yml"])

(deftest a-build-renders-the-whole-tree-and-runs-no-tool
  (let [workdir (temp-dir)
        result (wf/run sut/workflow (assoc valid :workdir workdir
                                           :green/event :build))]
    (try
      (is (= 0 (:green/exit result)))
      (doseq [path expected-build-artifacts]
        (is (.exists (io/file workdir "test" path))
            (str path " should be rendered")))
      (finally (delete-tree! workdir)))))

(deftest a-failing-start-stops-the-run-before-any-stage
  (let [workdir (temp-dir)
        result (wf/run sut/workflow (assoc valid :workdir workdir
                                           :green/event :build
                                           :provider-compute "nope"))]
    (try
      (is (= 2 (:green/exit result)))
      (is (not (.exists (io/file workdir "test")))
          "nothing should have been rendered")
      (finally (delete-tree! workdir)))))
