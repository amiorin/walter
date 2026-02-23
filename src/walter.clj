(ns walter
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.render :as render]
   [big-config.run :as run]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [com.rpl.specter :as s]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::workflow/end)
               (step-fns/->print-error-step-fn ::workflow/end)])

(defn tofu
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::tofu
                                ::render/templates [{:template "alpha"
                                                     :overwrite true
                                                     :transform [["tofu"
                                                                  :raw]]}]}
                               opts)]
    (workflow/run-steps step-fns opts)))

(defn tofu*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (tofu step-fns opts)))

(comment
  (debug tap-values
    (tofu* "render tofu:plan" {::bc/env :repl}))
  (-> tap-values))

(defn ansible
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::ansible
                                ::render/templates [{:template "alpha"
                                                     :overwrite true
                                                     :data-fn 'ansible/data-fn
                                                     :transform [["ansible"
                                                                  :raw]
                                                                 ['ansible/render "roles/users/tasks"
                                                                  {:packages "packages.yml"
                                                                   :repos "repos.yml"
                                                                   :ssh-config "ssh-config.yml"}
                                                                  :raw]
                                                                 ['ansible/render
                                                                  {:inventory "inventory.json"
                                                                   :config "default.config.yml"}
                                                                  :raw]]}]}
                               opts)]
    (workflow/run-steps step-fns opts)))

(defn ansible*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (ansible step-fns opts)))

(comment
  (debug tap-values
    (ansible* "render" {::bc/env :repl}))
  (-> tap-values))

(defn extract-params
  [opts]
  (let [ip (-> (p/shell {:dir (workflow/path opts ::tofu)
                         :out :string} "tofu show --json")
               :out
               (json/parse-string keyword)
               (->> (s/select-one [:values :root_module :resources s/FIRST :values :ipv4_address])))]
    {::workflow/params {:ip ip}}))

(defn resource-create
  [step-fns {:keys [::tofu-opts ::ansible-opts] :as opts}]
  (let [globals-opts (workflow/select-globals opts)
        tofu-opts (merge (workflow/parse-args "render tofu:init tofu:apply:-auto-approve")
                         globals-opts
                         tofu-opts)
        ansible-opts (merge (workflow/parse-args "render ansible-playbook:main.yml")
                            globals-opts
                            ansible-opts)
        opts* (atom opts)
        wf (core/->workflow {:first-step ::start
                             :wire-fn (fn [step step-fns]
                                        (case step
                                          ::start [core/ok ::tofu]
                                          ::tofu [(partial tofu step-fns) ::ansible]
                                          ::ansible [(partial ansible step-fns) ::end]
                                          ::end [identity]))
                             :next-fn (fn [step next-step {:keys [::bc/exit] :as opts}]
                                        (if (#{::tofu ::ansible} step)
                                          (do
                                            (swap! opts* merge (select-keys opts [::bc/exit ::bc/err]))
                                            (swap! opts* assoc step opts))
                                          (reset! opts* opts))
                                        (cond
                                          (= step ::end)
                                          [nil @opts*]

                                          (> exit 0)
                                          [::end @opts*]

                                          :else
                                          [next-step (case next-step
                                                       ::tofu tofu-opts
                                                       ::ansible (merge-with merge ansible-opts (extract-params @opts*))
                                                       @opts*)]))})]
    (wf step-fns opts)))

(defn resource-delete
  [step-fns opts]
  (let [opts (merge (workflow/parse-args "render tofu:destroy:-auto-approve")
                    opts)]
    (tofu step-fns opts)))

(defn resource
  [step-fns opts]
  (let [opts (merge {::workflow/create-fn resource-create
                     ::workflow/delete-fn resource-delete}
                    opts)
        wf (core/->workflow {:first-step ::start
                             :last-step ::end-comp
                             :wire-fn (fn [step step-fns]
                                        (case step
                                          ::start [(partial workflow/run-steps step-fns) ::end-comp]
                                          ::end-comp [identity]))})]
    (wf step-fns opts)))

(comment
  (debug tap-values
    (let [profile "insta-a"]
      (resource step-fns
                {::bc/env :repl
                 ::workflow/steps [:create]
                 ::render/profile profile
                 ::workflow/prefix (format ".dist/%s" profile)
                 ::workflow/create-opts {::tofu-opts (workflow/parse-args "render tofu:init")
                                         ::ansible-opts (workflow/parse-args "render")}
                 ::workflow/delete-opts (workflow/parse-args "render")})))
  (-> tap-values))

(defn resource*
  [args & [opts]]
  (let [profile "insta-a"
        opts (merge (workflow/parse-args args)
                    {::bc/env :shell
                     ::render/profile profile
                     ::workflow/prefix (format ".dist/%s" profile)}
                    opts)]
    (resource step-fns opts)))

(comment
  (debug tap-values
    (resource* "create" {::bc/env :repl}))
  (-> tap-values))
