(ns io.github.bigconfig-ai.walter.tools
  "Walter's own stage: the Ansible run that turns a provisioned host into a
  development workstation.

  The compute stage and the local ~/.ssh/config stage are Once's, reused
  unchanged. What Walter adds is `ansible-remote`: a static role tree plus five
  files generated from the `:walter` block of desired state."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [green.ansible :as ansible]
   [green.scaffold :as sc]
   [green.yaml :as yaml]
   [io.github.bigconfig-ai.once.tools :as once]))

(def ^:private template-root "io.github.bigconfig-ai.walter.tools")
(def ^:private raw-template :io.github.bigconfig-ai.walter/raw)
(def ^:private template-opts {:tag-open \<
                              :tag-close \>
                              :filter-open \{
                              :filter-close \}})

(defn tool-dir
  "The isolated working directory for `tool`. Walter shares Once's layout,
  <workdir>/<profile>/<tool>, so both packages' stages sit side by side."
  [opts tool]
  (once/tool-dir opts tool))

(defn backend-credential-env
  "Environment additions for a process that only reads OpenTofu state, such as
  `tofu output`. The launcher calls it to adopt existing state before a delete."
  [opts]
  (once/backend-credential-env opts))

(defn- template-spec
  [template target data]
  {:template template
   :target target
   :data data
   :opts template-opts})

(defn- raw-spec
  [target content]
  (template-spec raw-template target {:content content}))

;;; -------------------------------------------------------------- the static tree

(defn- ansible-template
  "Classpath keyword for `path` under the Ansible template tree:
  \"roles/root/tasks/main.yml\" ->
  :io.github.bigconfig-ai.walter.tools.ansible.roles.root.tasks/main.yml"
  [path]
  (let [segments (str/split path #"/")]
    (keyword (str/join "." (cons (str template-root ".ansible") (butlast segments)))
             (last segments))))

(defn- resource-content
  [template]
  (let [path (sc/template-path template)]
    (if-let [res (io/resource path)]
      (slurp res)
      (throw (ex-info (str "template not found on classpath: " path)
                      {:template template :path path})))))

(defn- copy-spec
  "Copy a template through the one-line `raw` template, so its contents reach
  the work directory untouched.

  Walter's Ansible tree carries no Selmer variables — everything that varies is
  generated — and roles/users/files/xterm-ghostty is terminfo source containing
  `%<%t` and `\\E[<%i`, which Selmer's <% %> tag delimiters would try to parse."
  [path target]
  (raw-spec target (resource-content (ansible-template path))))

(def ^:private static-files
  "Rendered verbatim into the work directory, in this order."
  ["ansible.cfg"
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
   "roles/users/tasks/main.yml"])

;;; -------------------------------------------------------------- data

(defn data-fn
  "Shape flat opts into the data the generators read.

  The workstation lists come from desired state under `:walter`; `ip`, `sudoer`
  and `uid` come from the compute stage's outputs. A user that names no `:uid`
  inherits the one the image's default user already has, because the play
  creates that user by uid and a clash would fail."
  ([data] (data-fn data nil))
  ([{:keys [compute-pubkey ip sudoer uid walter] :as data} _]
   (let [{:keys [users repos packages tailnet]} walter
         users (mapv #(assoc % :uid (or (:uid %) uid "1000")) users)
         config {:users (filterv (complement :remove) users)
                 :remove_users (filterv :remove users)
                 :atuin_login "{{ lookup('ansible.builtin.env', 'ATUIN_LOGIN') }}"
                 :ssh_key compute-pubkey}]
     (merge data {:sudoer (or sudoer "root")
                  :hosts [(or ip "192.168.0.1")]
                  :users users
                  :repos (vec repos)
                  :packages (vec packages)
                  :tailnet tailnet
                  :config config}))))

(defn- main-user
  "The user the workstation belongs to — the first one desired state keeps."
  [{:keys [users]}]
  (or (some (fn [{:keys [name remove]}] (when-not remove name)) users)
      "ubuntu"))

;;; -------------------------------------------------------------- generated files

(defn packages
  [{:keys [packages]}]
  (-> (for [[package cli] packages]
        [{:name (format "Add devbox package %s" package)
          :args {:creates (format ".local/share/devbox/global/default/.devbox/nix/profile/default/bin/%s" cli)}
          "ansible.builtin.shell" (format ". /etc/profile.d/nix.sh && devbox global add --disable-plugin %s" package)}])
      flatten
      yaml/generate-string))

(defn config
  [{:keys [config]}]
  (yaml/generate-string config))

(defn ssh-config
  "Tasks that add a `Host` block per workstation host to the remote user's
  ~/.ssh/config, reaching the box over the tailnet named in desired state."
  [{:keys [hosts tailnet] :as data}]
  (let [user (main-user data)]
    (-> (for [host hosts]
          [{:name (format "Add a new host entry using blockinfile for %s" host)
            "ansible.builtin.blockinfile" {:path "~/.ssh/config"
                                           :create true
                                           :block (format "Host %s
  Hostname %s.%s
  User %s
  ForwardAgent yes " host host tailnet user)
                                           :marker (format "# {mark} ANSIBLE MANAGED BLOCK FOR %s" host)
                                           :state "present"}}])
        flatten
        yaml/generate-string)))

(defn repos
  [{:keys [repos]}]
  (-> (for [{:keys [user org repo branch worktrees]} repos]
        (let [when-p (format "inventory_hostname.startswith(\"%s\")" user)]
          [{:name (format "Clone repo %s/%s" org repo)
            "ansible.builtin.shell" (format "ssh -o StrictHostKeyChecking=accept-new git@github.com || true && git clone git@github.com:%s/%s %s/%s" org repo repo branch)
            :args {:chdir "code/personal"
                   :creates (format "%s/%s" repo branch)}
            :when when-p}
           (for [worktree worktrees]
             {:name (format "Create the worktree %s for repo %s/%s" worktree org repo)
              "ansible.builtin.shell" (format "git fetch --all --tags && git worktree add ../%s %s" worktree worktree)
              :args {:chdir (format "code/personal/%s/%s" repo branch)
                     :creates (format "../%s" worktree)}
              :when when-p})]))
      flatten
      yaml/generate-string))

(defn inventory
  [{:keys [sudoer hosts users]}]
  (let [users (->> users
                   (filter (complement :remove))
                   (mapcat (fn [user] (map #(assoc user :host %) hosts))))
        admins (mapcat (fn [admin] (map #(assoc admin :host % :name sudoer) hosts))
                       [{:ansible_user sudoer}])
        users-hosts (reduce (fn [result {:keys [name uid host]}]
                              (assoc result (format "%s@%s" name host)
                                     {:ansible_host host
                                      :ansible_user name
                                      :uid uid}))
                            {}
                            users)
        admins-hosts (reduce (fn [result {:keys [name host]}]
                               (assoc result (format "root@%s" host)
                                      {:ansible_host host
                                       :ansible_user name}))
                             {}
                             admins)
        result {:all {:children {:admin {:hosts admins-hosts}
                                 :users {:hosts users-hosts}}}}]
    (json/generate-string result {:pretty true})))

(defn render
  [target data]
  (case target
    :packages (packages data)
    :repos (repos data)
    :ssh-config (ssh-config data)
    :inventory (inventory data)
    :config (config data)))

;;; -------------------------------------------------------------- steps

(defn tofu-compute-step
  "Once's compute stage, with its outputs merged into opts.

  Once merges them at its DNS join; Walter has no join, so the Ansible stages
  that follow read `ip`, `sudoer` and `uid` from the top level here."
  [opts]
  (let [result (once/tofu-compute-step opts)]
    (if (pos? (:green/exit result 0))
      result
      (merge result (:once/compute-params result)))))

(defn- ansible-remote-specs
  [opts]
  (let [dir (tool-dir opts "ansible-remote")
        data (data-fn opts)]
    (-> (mapv (fn [path] (copy-spec path (str dir "/" path))) static-files)
        (conj (raw-spec (str dir "/inventory.json") (inventory data))
              (raw-spec (str dir "/default.config.yml") (config data))
              (raw-spec (str dir "/roles/users/tasks/packages.yml") (packages data))
              (raw-spec (str dir "/roles/users/tasks/repos.yml") (repos data))
              (raw-spec (str dir "/roles/users/tasks/ssh-config.yml") (ssh-config data))))))

(defn ansible-remote-step
  [opts]
  (let [dir (tool-dir opts "ansible-remote")
        rendered (sc/scaffold opts (ansible-remote-specs opts))]
    (if (or (= :build (:green/event opts))
            (= :delete (:green/event opts)))
      rendered
      (ansible/ansible-step rendered {:dir dir
                                      :inventory "inventory.json"
                                      :playbooks {:create "main.yml"}
                                      :host-key-checking false}))))

(defn ansible-local-step
  "Once's local stage, which manages the `Host <profile>` block in
  ~/.ssh/config."
  [opts]
  (once/ansible-local-step opts))
