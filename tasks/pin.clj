(ns pin
  "Stamp the bundled launcher with the commits a standalone copy should
  resolve.

  This is a maintainer command for this repository, not something a user of the
  skill ever runs: it reads the HEAD of the checkout it is invoked in, so
  anywhere else it would stamp an unrelated SHA. That is why it lives in bb.edn
  rather than in the launcher — a payload copied into a stranger's project
  should not carry a command that is wrong by construction there.

  `once-sha` and `green-sha` move too when ONCE_LIB_ROOT or GREEN_LIB_ROOT
  point at a checkout, because a change that spans repositories has to pin all
  of them."
  (:require
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

(def ^:private launcher "green-walter/green")

(defn- git
  [dir & args]
  (apply sh/sh "git" "-C" (str dir) args))

(defn- git-out
  [dir & args]
  (let [{:keys [exit out]} (apply git dir args)]
    (when (zero? exit) (str/trim out))))

(defn- repo-head
  "HEAD of the repository containing `dir`, once it is clean and pushed.
  Returns [sha nil] or [nil error]. Pinning a dirty or unpushed commit would
  produce a launcher that resolves to something nobody else can fetch."
  [dir label]
  (if-let [top (git-out dir "rev-parse" "--show-toplevel")]
    (let [dirty (git-out top "status" "--porcelain")
          sha (git-out top "rev-parse" "HEAD")
          remotes (git-out top "branch" "-r" "--contains" (str sha))]
      (cond
        (seq dirty)
        [nil (str label " working tree is dirty; commit before pinning")]

        (not (str/includes? (str remotes) "origin/"))
        [nil (str label " HEAD " (subs sha 0 7) " is not on any remote branch; "
                  "push before pinning")]

        :else [sha nil]))
    [nil (str label " is not a git repository: " dir)]))

(defn- current-pin
  [text sym]
  (second (re-find (re-pattern (str "\\(def \\^:private " sym " \"([0-9a-f]{40})\"\\)"))
                   text)))

(defn- replace-pin
  [text sym old new]
  (str/replace text
               (str "(def ^:private " sym " \"" old "\")")
               (str "(def ^:private " sym " \"" new "\")")))

(def ^:private pins
  "Launcher symbol -> [label, how to find the checkout]. Walter is this
  repository; the other two are only pinned when their working tree is named,
  so a walter-only change does not silently move them."
  [["walter-sha" "walter" (constantly ".")]
   ["once-sha" "once" #(System/getenv "ONCE_LIB_ROOT")]
   ["green-sha" "green" #(System/getenv "GREEN_LIB_ROOT")]])

(defn- resolve-pin
  [text [sym label root-fn]]
  (let [current (current-pin text sym)
        root (root-fn)
        [head err] (if root (repo-head root label) [nil nil])]
    {:sym sym :label label :current current :head head :err err}))

(defn pin
  "Returns green's Unix-style outcome map, so the task can be tested."
  []
  (let [text (slurp launcher)
        resolved (mapv #(resolve-pin text %) pins)
        missing (remove :current resolved)
        errs (keep :err resolved)
        stale (filter (fn [{:keys [head current]}] (and head (not= head current)))
                      resolved)]
    (cond
      (seq missing)
      {:green/exit 2
       :green/err (str "could not locate the pins in " launcher ": "
                       (str/join ", " (map :sym missing)))}

      (seq errs) {:green/exit 2 :green/err (first errs)}

      (empty? stale)
      {:green/exit 0
       :green/err (str "already pinned to "
                       (subs (:current (first resolved)) 0 7))}

      :else
      (do
        (spit launcher
              (reduce (fn [text {:keys [sym current head]}]
                        (replace-pin text sym current head))
                      text
                      stale))
        {:green/exit 0
         :green/err (str/join "\n"
                              (map (fn [{:keys [label current head]}]
                                     (str "pinned " label " to " (subs head 0 7)
                                          " (was " (subs current 0 7) ")"))
                                   stale))}))))

(defn -main
  [& _]
  (let [{:green/keys [exit err]} (pin)]
    (when err
      (binding [*out* (if (zero? exit) *out* *err*)]
        (println err)))
    (System/exit exit)))
