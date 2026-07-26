(ns io.github.bigconfig-ai.walter.tools-test
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.bigconfig-ai.walter.tools :as tools]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "walter-tools-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree!
  [path]
  (doseq [f (reverse (file-seq (io/file path)))]
    (io/delete-file f true)))

(def ^:private walter-state
  {:tailnet "example-tailnet.ts.net"
   :users [{:name "ubuntu" :doomemacs "abc123" :remove false}
           {:name "retired" :remove true}]
   :repos [{:user "ubuntu" :org "acme" :repo "one" :branch "main" :worktrees []}
           {:user "ubuntu" :org "acme" :repo "two" :branch "main" :worktrees ["side"]}]
   :packages [["ripgrep" "rg"] ["fish" "fish"]]})

(defn- opts
  [workdir event]
  {:workdir workdir
   :profile "test"
   :green/event event
   :ip "203.0.113.10"
   :user "ubuntu"
   :sudoer "root"
   :uid "1001"
   :compute-pubkey "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY test@example.com"
   :walter walter-state})

;;; -------------------------------------------------------------- data

(deftest data-fn-reads-the-workstation-from-desired-state
  (let [data (tools/data-fn (opts "." :build) nil)]
    (is (= ["203.0.113.10"] (:hosts data)))
    (is (= "root" (:sudoer data)))
    (is (= "example-tailnet.ts.net" (:tailnet data)))
    (testing "a user that names no uid inherits the image's default"
      (is (= "1001" (:uid (first (:users data))))))
    (testing "removed users are split out for the play"
      (is (= ["ubuntu"] (mapv :name (get-in data [:config :users]))))
      (is (= ["retired"] (mapv :name (get-in data [:config :remove_users])))))
    (testing "the authorized key comes from :compute-pubkey"
      (is (= (:compute-pubkey (opts "." :build))
             (get-in data [:config :ssh_key]))))))

(deftest data-fn-honours-an-explicit-uid
  (let [data (tools/data-fn (assoc-in (opts "." :build) [:walter :users]
                                      [{:name "ubuntu" :uid "4242"}])
                            nil)]
    (is (= "4242" (:uid (first (:users data)))))))

;;; -------------------------------------------------------------- generated files

(deftest inventory-groups-the-sudoer-and-the-users
  (let [data (tools/data-fn (opts "." :build) nil)
        inventory (json/parse-string (tools/inventory data) true)]
    (is (= {:ansible_host "203.0.113.10" :ansible_user "root"}
           (get-in inventory [:all :children :admin :hosts (keyword "root@203.0.113.10")])))
    (is (= {:ansible_host "203.0.113.10" :ansible_user "ubuntu" :uid "1001"}
           (get-in inventory [:all :children :users :hosts (keyword "ubuntu@203.0.113.10")])))
    (testing "a removed user is not in the inventory"
      (is (nil? (get-in inventory [:all :children :users :hosts
                                   (keyword "retired@203.0.113.10")]))))))

(deftest ssh-config-reaches-the-host-over-the-configured-tailnet
  (let [yaml (tools/ssh-config (tools/data-fn (opts "." :build) nil))]
    (is (str/includes? yaml "Hostname 203.0.113.10.example-tailnet.ts.net"))
    (is (str/includes? yaml "User ubuntu"))
    (testing "the Jinja mark placeholder survives the renderer"
      (is (str/includes? yaml "# {mark} ANSIBLE MANAGED BLOCK FOR 203.0.113.10")))))

(deftest packages-check-for-the-cli-not-the-package-name
  (let [yaml (tools/packages (tools/data-fn (opts "." :build) nil))]
    (is (str/includes? yaml "Add devbox package ripgrep"))
    (is (str/includes? yaml "profile/default/bin/rg"))))

(deftest repos-clone-each-repo-and-add-its-worktrees
  (let [yaml (tools/repos (tools/data-fn (opts "." :build) nil))]
    (is (str/includes? yaml "Clone repo acme/one"))
    (is (str/includes? yaml "Clone repo acme/two"))
    (is (str/includes? yaml "Create the worktree side for repo acme/two"))
    (testing "each task is gated on the owning user"
      (is (str/includes? yaml "inventory_hostname.startswith(\\\"ubuntu\\\")")))))

;;; -------------------------------------------------------------- scaffolding

(def ^:private expected-files
  ["ansible.cfg"
   "default.config.yml"
   "inventory.json"
   "main.yml"
   "roles/root/files/Z50-devbox.sh"
   "roles/root/files/direnv.sh"
   "roles/root/files/ssh-agent.sh"
   "roles/root/files/zellij.sh"
   "roles/root/handlers/main.yml"
   "roles/root/tasks/caddy.yml"
   "roles/root/tasks/main.yml"
   "roles/root/tasks/redis.yml"
   "roles/users/files/xterm-ghostty"
   "roles/users/tasks/main.yml"
   "roles/users/tasks/packages.yml"
   "roles/users/tasks/repos.yml"
   "roles/users/tasks/ssh-config.yml"])

(deftest ansible-remote-build-renders-the-whole-tree-and-delete-removes-it
  (let [workdir (temp-dir)
        dir (io/file workdir "test" "ansible-remote")]
    (try
      (let [result (tools/ansible-remote-step (opts workdir :build))]
        (is (= 0 (:green/exit result)))
        (doseq [path expected-files]
          (is (.exists (io/file dir path)) (str path " should be rendered"))))

      (testing "delete removes every target it rendered"
        (let [result (tools/ansible-remote-step (opts workdir :delete))]
          (is (= 0 (:green/exit result)))
          (doseq [path expected-files]
            (is (not (.exists (io/file dir path))) (str path " should be gone")))))
      (finally (delete-tree! workdir)))))

(deftest static-files-are-copied-verbatim
  (let [workdir (temp-dir)]
    (try
      (tools/ansible-remote-step (opts workdir :build))
      (testing "terminfo source survives, though it contains Selmer's <% delimiter"
        (let [source (slurp (io/resource (str "io/github/bigconfig-ai/walter/tools/ansible"
                                              "/roles/users/files/xterm-ghostty")))
              rendered (slurp (io/file workdir "test" "ansible-remote"
                                       "roles/users/files/xterm-ghostty"))]
          (is (str/includes? source "<%"))
          (is (= source rendered))))
      (finally (delete-tree! workdir)))))

(deftest tool-dir-follows-the-once-layout
  (is (= (str (io/file ".green" "test" "ansible-remote"))
         (tools/tool-dir {:workdir ".green" :profile "test"} "ansible-remote"))))
