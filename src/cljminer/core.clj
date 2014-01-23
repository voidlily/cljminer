(ns cljminer.core
  (:require [clojure.java.io :as io]
            [pandect.core :refer (sha1)]
            [clj-time.core :refer (now)]
            [clj-time.coerce :refer (to-long)]
            [clj-jgit.porcelain :refer (with-repo git-add)]
            [clojure.java.shell :refer (with-sh-dir sh)]))

(def difficulty (atom nil))
(def nonce-seq (iterate inc 0N))

;;; Given a sequence starting from 0
;;; Convert each entry to base 36
;;; Hash it and make a commit object
;;; See if that commit objects meets the difficulty

;;; To parallelize:
;;; Some kind of signal sending to kill stuff?
;;; pmap deflates
;;; (->> nonce-seq (pmap craft-commit) (filter within-difficulty) first)
;;; future work for competion
;;; wrap this in a future with a 15 minute timeout?
;;; if i don't find one in 15 minutes, shutdown-agents and start over?

;;; TODO still need to add file, write tree

(defn commit-content [tree parent timestamp nonce]
  "Generate a git commit object body"
  (str "tree " tree "\n"
       "parent " parent "\n"
       "author CTF user <me@example.com> " timestamp " +0000" "\n"
       "committer CTF user <me@example.com> " timestamp " +0000" "\n"
       "\n"
       "Give me a Gitcoin" "\n"
       "\n"
       nonce))

(defrecord Commit [store hash])

(defn commit-object [content]
  "Given a commit body, generate the full commit object including hash."
  (let [header (str "commit " (.length content) (char 0))
        store (str header content)
        hash (sha1 store)]
    (Commit. store hash)))

(defn filename [repo-path commit]
  (let [hash (:hash commit)
        dirname (subs hash 0 2)
        filename (subs hash 2)
        full-path (str repo-path "/.git/objects/" dirname "/" filename)]
    (io/make-parents full-path)
    full-path))

(defn deflate-commit [commit repo-path]
  (with-open [w (-> (filename repo-path commit)
                    io/output-stream
                    java.util.zip.DeflaterOutputStream.
                    io/writer)]
    (.write w (:store commit))))

(defn within-difficulty [commit difficulty]
  "Is the commit's hash lexicographically less than the difficulty?"
  (< (compare (:hash commit) difficulty) 0))

(defn nonce-to-string [x]
  (Integer/toString x 36))

(defn find-first-commit [difficulty tree parent timestamp]
  (->> nonce-seq
       (pmap #(commit-object (commit-content tree parent timestamp %)))
       (filter #(within-difficulty % difficulty))
       first))

(defn get-difficulty [repo]
  (let [difficulty-file (str repo "/difficulty.txt")
        difficulty-contents (slurp difficulty-file)]
    (first (clojure.string/split-lines difficulty-contents))))

(defn get-parent[repo]
  "For a given repo, get the sha of master"
  (with-repo repo
    (-> repo
        .getRepository
        (.getRef "refs/heads/master")
        .getObjectId
        .getName)))

(defn add-ledger [repo]
  (with-repo repo
    (git-add repo "LEDGER.txt")))

(defn write-tree
  ([repo] (write-tree repo "git write-tree"))
  ([repo write-tree-cmd]
     (with-sh-dir repo
       (:out (sh write-tree-cmd)))))

(defn increment-line [user amount]
  (str user ": " (inc (Integer/parseInt amount))))

(defn process-line [line username]
  (if (or
       (re-matches #"^Private Gitcoin Ledger" line)
       (re-matches #"^======" line))
    line
    (do
      (let [[user amount] (clojure.string/split line #": ")]
        (if (= user username)
          (increment-line user amount)
          line)))))

(defn add-user-to-ledger [ledger username]
  (str ledger "\n" username ": 1"))

(defn update-ledger [repo username]
  (let [ledger-file (str repo "/LEDGER.txt")
        new-ledger-file (str repo "/LEDGER-NEW.txt")]
    ;; TODO use buffered reader/writer if this gets too big
    (let [ledger (clojure.string/trim (slurp ledger-file))
          lines (clojure.string/split-lines ledger)]
      (if (re-find (re-pattern (str username ":")) ledger)
        (let [new-lines (map #(process-line % username) lines)]
          (spit new-ledger-file (clojure.string/join "\n" new-lines)))
        (spit new-ledger-file (add-user-to-ledger ledger username))))
    ;; (with-open [rdr (io/reader ledger-file)
    ;;             w (io/writer new-ledger-file)]
    ;;   (doseq [line (line-seq rdr)]
    ;;     (.write w (str (process-line line username) "\n"))))
    ;;
    (.renameTo (io/file new-ledger-file) (io/file ledger-file))))

(defn reset-branch [repo]
  5)

(defn push-master [repo]
  5)

(defn run [repo username]
  (let [difficulty (get-difficulty repo)
        parent (get-parent repo)
        _ (update-ledger repo username)
        _ (add-ledger repo)
        tree (write-tree repo)
        timestamp (to-long (now))]
    (find-first-commit difficulty parent tree timestamp)
    (reset-branch repo) ;; might be able to use jgit here
    (println "Mined a gitcoin!") ; TODO print commit message?
    (push-master repo) ;; shell out
    ))
