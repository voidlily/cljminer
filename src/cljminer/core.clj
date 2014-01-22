(ns cljminer.core
  (:require [clojure.java.io :as io]
            [pandect.core :refer (sha1)]
            [clj-time.core :refer (now)]
            [clj-time.coerce :refer (to-long)]))

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

(defn commit-content [tree parent nonce]
  "Generate a git commit object body"
  (let [timestamp (to-long (now))]
    (str "tree " tree "\n"
         "parent " parent "\n"
         "author CTF user <me@example.com> " timestamp " +0000" "\n"
         "committer CTF user <me@example.com> " timestamp " +0000" "\n"
         "\n"
         "Give me a Gitcoin" "\n"
         "\n"
         nonce)))

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
        filename (subs hash 2)]
    (str repo-path ".git/objects/" dirname "/" filename)))

(defn deflate-commit [commit repo-path]
  (with-open [w (-> (filename repo-path)
                    io/output-stream
                    java.util.zip.DeflaterOutputStream.
                    io/writer)]
    (.write w (:store commit))))

(defn within-difficulty [commit difficulty]
  "Is the commit's hash lexicographically less than the difficulty?"
  (< (compare (:hash commit) difficulty) 0))

(defn nonce-to-string [x]
  (Integer/toString x 36))

(defn get-difficulty []
  5)

(defn get-repo []
  5)

(defn get-parent[repo]
  "For a given repo, get the sha of master"
  (with-repo repo
    (-> repo
        .getRepository
        (.getRef "refs/heads/master")
        .getObjectId
        .getName)))

(defn find-nonce [difficulty]
  5)

(defn -main []
  (let [difficulty (get-difficulty)
        repo (get-repo)
        base-commit (get-base-commit repo)]
    (find-nonce difficulty)
    )
  )
