(ns tool-wf
  (:require
   [big-config :as bc]
   [big-config.render :as render]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::end)
               (step-fns/->print-error-step-fn ::end)])

(defn tofu
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::tofu
                                ::render/templates [{:template "alpha"
                                                     :overwrite true
                                                     :transform [["tofu"]]}]}
                               opts)]
    (workflow/run-steps step-fns opts)))

(defn tofu*
  [args & [opts]]
  (let [profile "default"
        opts (merge (workflow/parse-args args)
                    {::bc/env :shell
                     ::render/profile profile
                     ::workflow/prefix (format ".dist/%s" profile)}
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
  (let [profile "default"
        opts (merge (workflow/parse-args args)
                    {::bc/env :shell
                     ::render/profile profile
                     ::workflow/prefix (format ".dist/%s" profile)}
                    opts)]
    (ansible step-fns opts)))

(comment
  (debug tap-values
    (ansible* "render" {::bc/env :repl}))
  (-> tap-values))
