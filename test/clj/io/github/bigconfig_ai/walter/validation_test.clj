(ns io.github.bigconfig-ai.walter.validation-test
  (:require
   [big-config :as bc]
   [big-config.render :as render]
   [big-config.workflow :as workflow]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.walter.ansible :as ansible]
   [io.github.bigconfig-ai.walter.cli :as cli]
   [io.github.bigconfig-ai.walter.options :as options]
   [io.github.bigconfig-ai.walter.validation :as v]))

(def ^:private test-compute-pubkey
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com")

(defn- with-creds
  [opts]
  (update opts ::workflow/params merge
          {:compute-pubkey test-compute-pubkey
           :hcloud-token "stub"
           :hcloud-name "walter"
           :hcloud-image "ubuntu-24.04"
           :hcloud-server-type "cx23"
           :hcloud-location "hel1"
           :hcloud-ssh-keys "stub-key"
           :do-token "stub"
           :digitalocean-name "walter"
           :digitalocean-region "ams3"
           :digitalocean-size "s-1vcpu-1gb-35gb-intel"
           :digitalocean-image "ubuntu-25-10-x64"
           :digitalocean-vpc-uuid "stub-vpc"
           :digitalocean-ssh-keys "stub-key"
           :oci-config-file-profile "DEFAULT"
           :oci-subnet-id "stub-subnet"
           :oci-compartment-id "stub-compartment"
           :oci-availability-domain "stub-ad"
           :oci-display-name "walter"
           :oci-shape "VM.Standard.A1.Flex"
           :oci-ocpus 1
           :oci-memory-in-gbs 4
           :oci-boot-volume-size-in-gbs 50
           :oci-boot-volume-vpus-per-gb 30
           :oci-ssh-authorized-keys "~/.ssh/id_ed25519.pub"
           :no-infra-compute-ip "192.0.2.10"
           :no-infra-compute-user "ubuntu"
           :no-infra-compute-sudoer "root"
           :no-infra-compute-uid "1000"
           :r2-bucket "stub-bucket"
           :r2-endpoint "https://stub.r2.cloudflarestorage.com"
           :r2-access-key-id "stub"
           :r2-secret-access-key "stub"
           :s3-bucket "stub-bucket"
           :s3-region "eu-west-1"}))

(defn- profile
  [provider-compute provider-backend]
  (-> {::render/profile "walter"
       ::workflow/params {:package "walter"
                          :provider-compute provider-compute
                          :provider-backend provider-backend}}
      with-creds))

(deftest active-profiles-pass-schema-with-stub-creds
  (doseq [[name profile] [["options/walter" (with-creds options/walter)]
                          ["hcloud+r2" (profile "hcloud" "r2")]
                          ["digitalocean+s3" (profile "digitalocean" "s3")]
                          ["no-infra+local" (profile "no-infra" "local")]]]
    (testing name
      (is (nil? (v/schema-errors profile))))))

(deftest placeholder-credential-is-reported
  (let [errors (v/schema-errors (assoc-in (profile "hcloud" "local")
                                          [::workflow/params :hcloud-token]
                                          "REPLACE_ME"))]
    (is (seq errors))
    (is (some #(and (str/includes? (:detail %) "hcloud-token")
                    (str/includes? (:detail %) "REPLACE_ME"))
              errors))))

(deftest blank-required-string-is-reported
  (let [errors (v/schema-errors (assoc-in (profile "hcloud" "local")
                                          [::workflow/params :hcloud-name]
                                          ""))]
    (is (seq errors))
    (is (some #(and (str/includes? (:detail %) "hcloud-name")
                    (str/includes? (:detail %) "non-empty"))
              errors))))

(deftest missing-compute-pubkey-is-reported
  (let [errors (v/schema-errors (update (profile "no-infra" "local")
                                        ::workflow/params dissoc :compute-pubkey))]
    (is (seq errors))
    (is (some #(str/includes? (:detail %) "compute-pubkey") errors))))

(deftest env-string-scalars-are-accepted
  (testing "boolean env override"
    (is (nil? (v/schema-errors (assoc-in (profile "no-infra" "local")
                                         [::workflow/params :compute-prevent-destroy]
                                         "false")))))
  (testing "integer env override"
    (is (nil? (v/schema-errors (assoc-in (profile "oci" "local")
                                         [::workflow/params :oci-ocpus]
                                         "2"))))))

(deftest validate-workflow-step-sets-exit-status
  (testing "valid report succeeds"
    (with-redefs [v/validate-report (constantly {:ok? true :errors []})]
      (let [result (atom nil)]
        (with-out-str
          (reset! result (v/validate [] {})))
        (is (= 0 (::bc/exit @result)))
        (is (= {:ok? true :errors []} (::v/result @result))))))
  (testing "invalid report fails"
    (with-redefs [v/validate-report (constantly {:ok? false
                                                 :errors [{:check :schema
                                                           :detail "bad"}]})]
      (let [result (atom nil)]
        (with-out-str
          (reset! result (v/validate [] {})))
        (is (= 1 (::bc/exit @result)))
        (is (= "validation failed" (::bc/err @result)))))))

(deftest provider-tools-picks-right-clis
  (testing "hcloud + s3"
    (is (= #{"hcloud" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "hcloud"
                                             :provider-backend "s3"}))))))
  (testing "oci + s3"
    (is (= #{"oci" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "oci"
                                             :provider-backend "s3"}))))))
  (testing "digitalocean + s3"
    (is (= #{"doctl" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "digitalocean"
                                             :provider-backend "s3"}))))))
  (testing "hcloud + r2"
    (is (= #{"hcloud" "aws"}
           (set (map :cmd (v/provider-tools {:provider-compute "hcloud"
                                             :provider-backend "r2"}))))))
  (testing "no-infra + local"
    (is (= #{}
           (set (map :cmd (v/provider-tools {:provider-compute "no-infra"
                                             :provider-backend "local"})))))))

(deftest tool-errors-honors-injected-which-fn
  (let [params     (::workflow/params (profile "hcloud" "s3"))
        which-stub #(not= % "tofu")
        errors     (v/tool-errors params which-stub)]
    (is (= 1 (count errors)))
    (is (str/includes? (:detail (first errors)) "OpenTofu"))))

(deftest ssh-agent-checks-cloud-compute-pubkey
  (let [params      {:provider-compute "hcloud"
                     :compute-pubkey test-compute-pubkey}
        key-id-line (str/join " " (take 2 (str/split test-compute-pubkey #"\s+")))]
    (testing "missing SSH_AUTH_SOCK is reported for cloud compute"
      (let [errors (#'v/ssh-agent-errors params {})]
        (is (= 1 (count errors)))
        (is (str/includes? (first errors) "SSH_AUTH_SOCK"))))
    (testing "no-infra skips the ssh-agent check"
      (is (empty? (#'v/ssh-agent-errors (assoc params :provider-compute "no-infra") {}))))
    (testing "loaded key is matched by type and body, ignoring comments"
      (with-redefs [v/run (fn
                            ([_args]
                             (throw (ex-info "unexpected one-arg run" {})))
                            ([args extra-env]
                             (is (= ["ssh-add" "-L"] args))
                             (is (= {"SSH_AUTH_SOCK" "/tmp/agent.sock"} extra-env))
                             {:ok? true :exit 0 :out (str key-id-line " other-comment\n") :err ""}))]
        (is (empty? (#'v/ssh-agent-errors params {"SSH_AUTH_SOCK" "/tmp/agent.sock"})))))
    (testing "missing loaded key is reported"
      (with-redefs [v/run (fn
                            ([_args]
                             (throw (ex-info "unexpected one-arg run" {})))
                            ([_args _extra-env]
                             {:ok? true :exit 0 :out "ssh-ed25519 AAAAother comment\n" :err ""}))]
        (let [errors (#'v/ssh-agent-errors params {"SSH_AUTH_SOCK" "/tmp/agent.sock"})]
          (is (= 1 (count errors)))
          (is (str/includes? (first errors) "not loaded")))))))

(deftest r2-head-bucket-errors-are-classified
  (is (= :missing-bucket (#'v/classify-head-bucket-error "An error occurred (404): Not Found")))
  (is (= :bad-credentials (#'v/classify-head-bucket-error "An error occurred (403): Forbidden")))
  (is (= :unknown (#'v/classify-head-bucket-error "connection reset"))))

(deftest ansible-data-checks-compute-pubkey
  (testing "valid generated data passes"
    (is (empty? (v/ansible-data-errors (profile "no-infra" "local")))))
  (testing "missing compute-pubkey is reported"
    (let [errors (v/ansible-data-errors (update (profile "no-infra" "local")
                                                ::workflow/params dissoc :compute-pubkey))]
      (is (seq errors))
      (is (some #(str/includes? (:detail %) ":ssh_key") errors)))))

(deftest ansible-data-checks-users-repos-and-packages
  (with-redefs [ansible/data-fn (fn [_params _]
                                  {:hosts [""]
                                   :users [{:name "" :uid "" :remove false}]
                                   :config {:ssh_key test-compute-pubkey}
                                   :repos [{:org "" :repo "walter" :branch "main" :user "ubuntu"}]
                                   :packages [["" "bb"]]})]
    (let [details (map :detail (v/ansible-data-errors (profile "no-infra" "local")))]
      (is (some #(str/includes? % "hosts[0]") details))
      (is (some #(str/includes? % "users[0]") details))
      (is (some #(str/includes? % "repos[0]") details))
      (is (some #(str/includes? % "packages[0]") details)))))

(deftest cli-exposes-only-package-validate
  (is (contains? cli/package-commands "validate"))
  (is (str/includes? cli/help-text "bb run package validate"))
  (is (not (str/includes? cli/help-text "bb run validate"))))
