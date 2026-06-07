(ns io.github.bigconfig-ai.walter.cli
  (:require
   [io.github.bigconfig-ai.once.tools :as tools-once]
   [io.github.bigconfig-ai.walter.options :as options]
   [io.github.bigconfig-ai.walter.package :as package]
   [io.github.bigconfig-ai.walter.params :as params]
   [io.github.bigconfig-ai.walter.tools :as tools-walter]))

(def help-text
  "Usage: bb run <command> [args...]

Commands:
  package <step>...       Run Walter package workflow steps for the active profile.
                            bb run package validate
                            bb run package describe
                            bb run package build
                            bb run package create
                            bb run package delete
                            bb run package git-check lock build unlock-any

  Package steps:
    validate              Pre-flight profile, tool, credential, and Ansible-data checks.
    describe              Providers, compute reachability, and workstation summary report.
    build                 Render Walter stages without applying/provisioning.
    create                Provision and configure the Walter workstation.
    delete                Destroy the compute Tofu stage.
    lock                  Acquire the BigConfig Git-tag lock.
    git-check             Verify the Git working tree/upstream state is clean.
    git-push              Run git push through the BigConfig workflow.
    unlock-any            Force-release the computed BigConfig lock tag.

  Individual tools (accept SDK workflow steps and exec commands):
  tofu <args>             e.g. bb run tofu render tofu:init tofu:apply:-auto-approve
                          e.g. bb run tofu git-check lock render tofu:init tofu:plan unlock-any
  ansible <args>          e.g. bb run ansible render -- ansible-playbook main.yml
  ansible-local <args>    e.g. bb run ansible-local render -- ansible-playbook main.yml

Notes:
  * When launched through `run`, the active profile comes from that script;
    otherwise it defaults to `bb` in io.github.bigconfig-ai.walter.options.
  * Any param can be overridden with BC_PAR_* environment variables.")

(def package-commands #{"validate" "describe" "build" "create" "delete"
                        "lock" "git-check" "git-push" "unlock-any"})

(defn- die!
  [& lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit 1))

(defn main*
  ([args]
   (main* args options/bb))
  ([args opts]
   (let [args (mapv str args)
         command (first args)
         rest-args (if (seq args) (subvec args 1) [])]
     (cond
       (or (nil? command) (#{"help" "--help" "-h"} command))
       (println help-text)

       (= command "package")
       (cond
         (some #{(first rest-args)} ["help" "--help" "-h"])
         (println help-text)

         (seq rest-args)
         (package/walter* rest-args opts)

         :else
         (die! "Missing package step."
               "Usage: bb run package <step>..."))

       (contains? package-commands command)
       (die! (str "Use `bb run package " command "`.") "" help-text)

       (= command "tofu")
       (tools-once/tofu* rest-args (params/walter-opts opts))

       (= command "ansible")
       (tools-walter/ansible* rest-args (params/walter-opts opts))

       (= command "ansible-local")
       (tools-once/ansible-local* rest-args (params/walter-opts opts))

       :else
       (die! (str "Unknown command: " command) "" help-text)))))

(defn -main
  [& args]
  (main* args))
