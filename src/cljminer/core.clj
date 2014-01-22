(ns cljminer.core)

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

(defn commit-body [tree parent timestamp nonce]
  "Generate a git commit object body"
  (str "tree " tree "\n"
       "parent " parent "\n"
       "author CTF user <me@example.com> " timestamp " +0000" "\n"
       "committer CTF user <me@example.com> " timestamp " +0000" "\n"
       "\n"
       "Give me a Gitcoin" "\n"
       "\n"
       nonce))

(defn within-difficulty [hash difficulty]
  "Is the hash lexicographically less than the difficulty?"
  (< (compare hash difficulty) 0))

(defn nonce-to-string [x]
  (Integer/toString x 36))

(defn get-difficulty []
  5)

(defn get-repo []
  5)

(defn get-base-commit [repo]
  5)

(defn find-nonce [difficulty]
  5)

(defn -main []
  (let [difficulty (get-difficulty)
        repo (get-repo)
        base-commit (get-base-commit repo)]
    (find-nonce difficulty)
    )
  )
