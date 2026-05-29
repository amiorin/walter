(ns io.github.bigconfig-ai.walter.cli
  (:require
   [io.github.bigconig-ai.once.tools :as tools-once]
   [io.github.bigconfig-ai.walter.options :as options]
   [io.github.bigconfig-ai.walter.package :as package]
   [io.github.bigconfig-ai.walter.params :as params]
   [io.github.bigconfig-ai.walter.tools :as tools-walter]))

(def help-text
  "Usage: bb run <command> [args...]

Commands:
  package <step>...       Build, provision, or tear down Walter infrastructure.
                            bb run package build
                            bb run package create
                            bb run package delete

  Individual tools (each requires `render` first):
  tofu <args>             e.g. bb run tofu render tofu:init tofu:apply:-auto-approve
  ansible <args>          e.g. bb run ansible render -- ansible-playbook main.yml
  ansible-local <args>    e.g. bb run ansible-local render -- ansible-playbook main.yml

Notes:
  * When launched through `run`, the active profile comes from that script;
    otherwise it defaults to `bb` in io.github.bigconfig-ai.walter.options.
  * Any param can be overridden with BC_PAR_* environment variables.")

(def package-commands #{"build" "create" "delete"})

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
       (if (seq rest-args)
         (package/walter* rest-args opts)
         (die! "Missing package step."
               "Usage: bb run package <build|create|delete>..."))

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
