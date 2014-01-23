(ns cljminer.core
  (:require [clojure.java.io :as io]
            [pandect.core :refer (sha1)]
            [clj-time.core :refer (now)]
            [clj-time.coerce :refer (to-long)]
            [clj-jgit.porcelain :refer (with-repo git-add git-reset git-fetch)]
            [clojure.java.shell :refer (with-sh-dir sh)]))

(def difficulty (atom nil))
;(def nonce-seq (iterate inc 0N))
(def nonce-seq (range 3000000))

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

;;; TODO better threading - core.async? channels?
;;; wrap a "done" state somewhere so it knows when to restart?

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

(defn commit-object [^String content]
  "Given a commit body, generate the full commit object including hash."
  (let [header (str "commit " (.length content) (char 0))
        store (str header content)
        hash (sha1 store)]
    (Commit. store hash)))

(defn filename [repo commit]
  (let [hash (:hash commit)
        dirname (subs hash 0 2)
        filename (subs hash 2)
        full-path (str repo "/.git/objects/" dirname "/" filename)]
    (io/make-parents full-path)
    full-path))

(defn deflate-commit [repo commit]
  (with-open [w (-> (filename repo commit)
                    io/output-stream
                    java.util.zip.DeflaterOutputStream.
                    io/writer)]
    (.write w (:store commit))))

(defn within-difficulty [commit difficulty]
  "Is the commit's hash lexicographically less than the difficulty?"
  (< (compare (:hash commit) difficulty) 0))

(defn nonce-to-string [x]
  (Integer/toString x 36))

(defn build-commit [tree parent timestamp nonce]
  (->> nonce
       nonce-to-string
       (commit-content tree parent timestamp)
       commit-object))

(defn find-first-commit [difficulty tree parent timestamp]
  (->> nonce-seq
       (pmap #(build-commit tree parent timestamp %))
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
  [repo]
  (with-sh-dir repo
    (-> (sh "git" "write-tree")
        :out
        clojure.string/split-lines
        first)))

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
  (str ledger "\n" username ": 1\n"))

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

(defn fetch-and-reset [repo]
  (with-sh-dir repo
    (sh "git" "fetch"))
  (with-repo repo
    (git-reset repo "origin/master" :hard)))

(defn reset-branch [repo commit]
  (with-repo repo
    (git-reset repo (:hash commit) :hard)))

(defn push-master [repo]
  (with-sh-dir repo
    (sh "git push origin master")))

(defn find-commit [repo username]
  (let [difficulty (get-difficulty repo)
        _ (update-ledger repo username)
        _ (add-ledger repo)
        tree (write-tree repo)
        parent (get-parent repo)
        timestamp (to-long (now))]
    (find-first-commit difficulty tree parent timestamp)))

(defn run [repo username]
  ;; TODO fetch master and reset
  (fetch-and-reset repo)
  (when-let [commit (find-commit repo username)]
    (deflate-commit repo commit)
    (reset-branch repo commit) ;; might be able to use jgit here
    (println "Mined a gitcoin!") ; TODO print commit message?
    (println (str "Commit: " commit))
    (push-master repo)
    commit) ;; shell out
  )

(def repo "/tmp/current-round")
(def username "user-j7hi8ian")

(defn go
  ([] (go 15000))
  ([timeout]
     (while true
       (let [attempt (future (run repo username))
             result (deref attempt timeout :timeout)]
         (println result)
         (if (= result :timeout)
           (future-cancel attempt))))))
;; TODO timeout 30 seconds?
