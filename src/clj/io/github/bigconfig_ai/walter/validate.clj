(ns io.github.bigconfig-ai.walter.validate
  "Desired-state validation: the two provider slots, and the workstation.

  Walter provisions with Once's OpenTofu templates, so the providers it accepts
  are exactly the ones Once describes — `providers` is Once's registry narrowed
  to the two slots Walter has, not a copy of it. A copy is how a package ends
  up validating against one set of keys and rendering from another: a required
  key added upstream would pass validation here and then fail in the template."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]
   [io.github.bigconfig-ai.once.validate :as once-validate]))

(def ^:private slots
  [:provider-compute :provider-backend])

(def providers
  "Provider slot -> provider name -> `{:required :secrets :tofu-env}`, taken
  from Once. Walter has no SMTP or DNS stage, so those two slots are dropped."
  (select-keys once-validate/providers slots))

(defn- entry
  [opts slot]
  (get-in providers [slot (get opts slot)]))

(defn- slot-keys
  [opts field]
  (mapcat #(get (entry opts %) field []) slots))

(defn placeholder?
  "Whether a value is missing in the ways a hand-edited EDN file produces:
  absent, blank, or still carrying the scaffold's REPLACE_ME."
  [x]
  (once-validate/placeholder? x))

(defn- missing-keys
  [opts ks]
  (keep (fn [k] (when (placeholder? (get opts k)) k)) ks))

(def ^:private domain-re
  #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def ^:private username-re #"^[a-z_][a-z0-9_-]*$")

;;; -------------------------------------------------------------- the workstation

(defn- user-errors
  [users]
  (mapcat
   (fn [[idx {:keys [name uid]}]]
     (concat
      (when (or (placeholder? name) (not (re-matches username-re (str name))))
        [(format ":walter :users[%d] has an invalid :name" idx)])
      ;; A user with no :uid inherits the compute stage's, which is the common
      ;; case; one that names a uid has to name a number.
      (when-not (or (nil? uid) (re-matches #"^\d+$" (str uid)))
        [(format ":walter :users[%d] :uid must be a number" idx)])))
   (map-indexed vector users)))

(defn- repo-errors
  [repos]
  (mapcat
   (fn [[idx {:keys [user org repo branch worktrees]}]]
     (concat
      (when (some placeholder? [user org repo branch])
        [(format ":walter :repos[%d] requires :user, :org, :repo and :branch" idx)])
      (when-not (or (nil? worktrees) (sequential? worktrees))
        [(format ":walter :repos[%d] :worktrees must be a sequence" idx)])))
   (map-indexed vector repos)))

(defn- package-errors
  [packages]
  (mapcat
   (fn [[idx package]]
     (when-not (and (sequential? package)
                    (= 2 (count package))
                    (not-any? placeholder? package))
       [(format ":walter :packages[%d] must be [package cli], both non-empty" idx)]))
   (map-indexed vector packages)))

(defn- walter-errors
  [{:keys [tailnet users repos packages] :as walter}]
  (if-not (map? walter)
    [":walter must be a map of the workstation's desired state"]
    (concat
     (when (or (placeholder? tailnet)
               (not (re-matches domain-re (str tailnet))))
       [":walter :tailnet must be a domain name"])
     (if-not (and (sequential? users) (seq users))
       [":walter :users must be a non-empty sequence"]
       (user-errors users))
     (when-not (sequential? repos)
       [":walter :repos must be a sequence"])
     (when (sequential? repos) (repo-errors repos))
     (when-not (sequential? packages)
       [":walter :packages must be a sequence"])
     (when (sequential? packages) (package-errors packages)))))

;;; -------------------------------------------------------------- top-level

(defn state-errors
  "Everything wrong with `opts` that does not depend on credentials, as a
  vector of messages. Empty means the desired state is renderable."
  [opts]
  (vec
   (concat
    (map #(str % " is required")
         (missing-keys opts (concat [:profile :workdir :compute-pubkey]
                                    (slot-keys opts :required))))
    (for [slot slots
          :let [provider (get opts slot)]
          :when (not (contains? (get providers slot) provider))]
      (str "unsupported " slot " " (pr-str provider)))
    (walter-errors (:walter opts))
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    ;; Unlike Once's :compute-pubkey, Walter's is load-bearing: it is the key
    ;; authorized for every user the play creates.
    (when-not (str/starts-with? (str (:compute-pubkey opts)) "ssh-")
      [":compute-pubkey must be an SSH public key"]))))

(defn secret-errors
  "Credentials the selected providers need that no `GREEN_PAR_*` variable
  supplied."
  [opts]
  (map #(str "required credential is not set: " (green-cli/par-name %))
       (distinct (missing-keys opts (slot-keys opts :secrets)))))
