(ns io.github.bigconfig-ai.walter.validate-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.once.validate :as once-validate]
   [io.github.bigconfig-ai.walter.validate :as sut]))

(def ^:private walter-state
  {:tailnet "example-tailnet.ts.net"
   :users [{:name "ubuntu" :doomemacs "abc123" :remove false}]
   :repos [{:user "ubuntu" :org "acme" :repo "one" :branch "main" :worktrees []}]
   :packages [["ripgrep" "rg"] ["fish" "fish"]]})

(def ^:private valid
  {:profile "test"
   :workdir ".green"
   :compute-pubkey "ssh-ed25519 AAAATEST me@example.com"
   :walter walter-state
   :provider-compute "no-infra"
   :provider-backend "local"
   :compute-prevent-destroy true
   :no-infra-compute-ip "203.0.113.10"
   :no-infra-compute-user "ubuntu"
   :no-infra-compute-sudoer "root"
   :no-infra-compute-uid "1000"})

(defn- errors-matching
  [opts substring]
  (filter #(str/includes? % substring) (sut/state-errors opts)))

;;; -------------------------------------------------------------- the registry

(deftest the-registry-is-once-narrowed-to-walters-two-slots
  (testing "walter accepts exactly the providers once describes"
    (is (= (select-keys once-validate/providers
                        [:provider-compute :provider-backend])
           sut/providers)))

  (testing "the slots walter has no stage for are absent"
    (is (nil? (:provider-smtp sut/providers)))
    (is (nil? (:provider-dns sut/providers))))

  (testing "the compute providers are the ones once ships templates for"
    (is (= #{"digitalocean" "hcloud" "oci" "no-infra"}
           (set (keys (:provider-compute sut/providers)))))
    (is (= #{"local" "s3" "r2"}
           (set (keys (:provider-backend sut/providers)))))))

;;; -------------------------------------------------------------- state

(deftest a-complete-desired-state-has-no-errors
  (is (= [] (sut/state-errors valid))))

(deftest every-provider-slot-must-name-a-known-provider
  (is (seq (errors-matching (assoc valid :provider-compute "nope")
                            "unsupported :provider-compute")))
  (is (seq (errors-matching (assoc valid :provider-backend "nope")
                            "unsupported :provider-backend"))))

(deftest missing-provider-keys-are-reported-per-provider
  (testing "a provider's required keys come from once's registry"
    (let [errors (sut/state-errors (dissoc valid :no-infra-compute-ip))]
      (is (seq (filter #(str/includes? % ":no-infra-compute-ip is required") errors)))))

  (testing "switching provider changes which keys are demanded"
    (let [errors (sut/state-errors (assoc valid :provider-backend "s3"))]
      (is (seq (filter #(str/includes? % ":s3-bucket is required") errors)))
      (is (seq (filter #(str/includes? % ":s3-region is required") errors))))))

(deftest placeholders-count-as-missing
  (doseq [placeholder [nil "" "   " "REPLACE_ME"]]
    (is (seq (errors-matching (assoc valid :no-infra-compute-user placeholder)
                              ":no-infra-compute-user is required"))
        (str "placeholder " (pr-str placeholder) " should not pass"))))

(deftest the-compute-pubkey-is-required-and-checked-for-shape
  (is (seq (errors-matching (dissoc valid :compute-pubkey) ":compute-pubkey")))
  (is (seq (errors-matching (assoc valid :compute-pubkey "not-a-key")
                            ":compute-pubkey must be an SSH public key"))))

(deftest prevent-destroy-must-be-a-boolean
  (is (seq (errors-matching (assoc valid :compute-prevent-destroy "false")
                            ":compute-prevent-destroy must be true or false"))))

;;; -------------------------------------------------------------- the workstation

(deftest the-walter-block-must-be-a-map
  (is (seq (errors-matching (dissoc valid :walter) ":walter must be a map")))
  (is (seq (errors-matching (assoc valid :walter [1 2]) ":walter must be a map"))))

(deftest the-tailnet-must-look-like-a-domain
  (doseq [bad ["REPLACE_ME" "not a domain" "nodots"]]
    (is (seq (errors-matching (assoc-in valid [:walter :tailnet] bad)
                              ":walter :tailnet"))
        (str (pr-str bad) " should not pass"))))

(deftest users-are-validated-individually
  (testing "at least one is required"
    (is (seq (errors-matching (assoc-in valid [:walter :users] [])
                              ":walter :users must be a non-empty sequence"))))

  (testing "the name has to be a usable unix account"
    (is (seq (errors-matching (assoc-in valid [:walter :users] [{:name "Not Valid"}])
                              ":walter :users[0] has an invalid :name"))))

  (testing "a uid is optional, because one is inherited from the image"
    (is (= [] (sut/state-errors (assoc-in valid [:walter :users] [{:name "ubuntu"}])))))

  (testing "but a uid that is present must be a number"
    (is (seq (errors-matching (assoc-in valid [:walter :users]
                                        [{:name "ubuntu" :uid "root"}])
                              ":walter :users[0] :uid must be a number")))))

(deftest repos-need-their-four-coordinates
  (is (seq (errors-matching (assoc-in valid [:walter :repos]
                                      [{:org "acme" :repo "one" :branch "main"}])
                            ":walter :repos[0] requires")))
  (is (seq (errors-matching (assoc-in valid [:walter :repos]
                                      [{:user "ubuntu" :org "acme" :repo "one"
                                        :branch "main" :worktrees "side"}])
                            ":walter :repos[0] :worktrees must be a sequence"))))

(deftest packages-are-package-cli-pairs
  (doseq [bad [["ripgrep"] ["ripgrep" "rg" "extra"] "ripgrep" ["ripgrep" ""]]]
    (is (seq (errors-matching (assoc-in valid [:walter :packages] [bad])
                              ":walter :packages[0] must be [package cli]"))
        (str (pr-str bad) " should not pass"))))

;;; -------------------------------------------------------------- secrets

(deftest secrets-are-demanded-per-selected-provider
  (testing "no-infra compute and local state need none"
    (is (= [] (sut/secret-errors valid))))

  (testing "hcloud wants its token"
    (is (= ["required credential is not set: GREEN_PAR_HCLOUD_TOKEN"]
           (sut/secret-errors (assoc valid :provider-compute "hcloud")))))

  (testing "digitalocean wants its own"
    (is (= ["required credential is not set: GREEN_PAR_DO_TOKEN"]
           (sut/secret-errors (assoc valid :provider-compute "digitalocean")))))

  (testing "oci authenticates from ~/.oci/config, so it asks for nothing"
    (is (= [] (sut/secret-errors (assoc valid :provider-compute "oci")))))

  (testing "r2 wants both halves of its key pair"
    (is (= ["required credential is not set: GREEN_PAR_R2_ACCESS_KEY_ID"
            "required credential is not set: GREEN_PAR_R2_SECRET_ACCESS_KEY"]
           (sut/secret-errors (assoc valid :provider-backend "r2")))))

  (testing "s3 uses the ambient AWS chain"
    (is (= [] (sut/secret-errors (assoc valid :provider-backend "s3")))))

  (testing "a supplied credential satisfies the check"
    (is (= [] (sut/secret-errors (assoc valid
                                        :provider-compute "hcloud"
                                        :hcloud-token "a-real-token"))))))
