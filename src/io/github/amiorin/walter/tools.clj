(ns io.github.amiorin.walter.tools
  (:require
   [big-config :as bc]
   [big-config.render :as render]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug keyword->path]]
   [big-config.workflow :as workflow]
   [io.github.amiorin.walter.ansible :as a]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::end)
               (step-fns/->print-error-step-fn ::end)])

(defn tofu
  [step-fns opts]
  (let [opts (workflow/prepare {::workflow/name ::tofu
                                ::render/templates [{:template (keyword->path ::tofu)
                                                     :overwrite true
                                                     :transform [["."]]}]}
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
                                ::render/templates [{:template (keyword->path ::ansible)
                                                     :overwrite true
                                                     :data-fn a/data-fn
                                                     :transform [["."
                                                                  :raw]
                                                                 [a/render "roles/users/tasks"
                                                                  {:packages "packages.yml"
                                                                   :repos "repos.yml"
                                                                   :ssh-config "ssh-config.yml"}
                                                                  :raw]
                                                                 [a/render
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
    (ansible* "render" {::bc/env :repl
                        ::workflow/params {:ip "89.167.101.16"}}))
  (-> tap-values))
