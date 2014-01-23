(ns cljminer.core
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.core.async.lab :as lab]
            [pandect.core :refer (sha1)]
            [clj-time.core :refer (now)]
            [clj-time.coerce :refer (to-long)]
            [clj-jgit.porcelain :refer (with-repo git-add git-reset git-fetch)]
            [clojure.java.shell :refer (with-sh-dir sh)])
  (:import java.security.MessageDigest))

(def difficulty (atom nil))
;(def nonce-seq (iterate inc 0N))
(def nonce-length 5)
(def max-nonce (Integer/parseInt (apply str (repeat nonce-length "a")) 36))
(def nonce-seq (range max-nonce))

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

;;; TODO figure out how to get base36 back
;;; given a max string "zzzzz" - be able to zero pad any variation of it

(defn commit-body-header [tree parent timestamp]
  "Separate out the commit body header for better incremental hashing"
  (let [content
        (str "tree " tree "\n"
             "parent " parent "\n"
             "author CTF user <me@example.com> " timestamp " +0000" "\n"
             "committer CTF user <me@example.com> " timestamp " +0000" "\n"
             "\n"
             "Give me a Gitcoin" "\n"
             "\n")]
    (str "commit " (+ nonce-length (.length content)) (char 0)
         content)))

(def ^:private ^"[B" hex-chars
  (byte-array (.getBytes "0123456789abcdef" "UTF-8")))

(defn bytes->hex
  "Convert Byte Array to Hex String
(from pandect)"
  ^String
  [^"[B" data]
  (let [len (alength data)
        ^"[B" buffer (byte-array (* 2 len))]
    (loop [i 0]
      (when (< i len)
        (let [b (aget data i)]
          (aset buffer (* 2 i) (aget hex-chars (bit-shift-right (bit-and b 0xF0) 4)))
          (aset buffer (inc (* 2 i)) (aget hex-chars (bit-and b 0x0F))))
        (recur (inc i))))
    (String. buffer "UTF-8")))

(defn digest->hexstring
  "Converts bytearray from MessageDigest.digest() to hex string
https://github.com/ray1729/clj-message-digest/blob/master/src/clj_message_digest/core.clj"
  [digest]
  (apply str (map (partial format "%02x") digest)))

(defn build-partial-digest
  "Build a partial digest of the commit body header. We add on the nonce and update the digest later."
  [^String body-header]
  (let [digest (MessageDigest/getInstance "SHA-1")
        header-bytes (.getBytes body-header "UTF-8")]
    (.update digest header-bytes)
    digest))

(defn compute-digest [^MessageDigest partial-digest ^String nonce]
  (let [^MessageDigest cloned-digest (.clone partial-digest)]
    (.update cloned-digest (.getBytes nonce "UTF-8"))
    (bytes->hex (.digest cloned-digest))))

(defn pad-nonce [^String nonce]
  (let [num-zeroes (- nonce-length (.length nonce))]
    (str (apply str (repeat num-zeroes "0")) nonce)))

(defn commit-content [body-header nonce]
  "Generate a git commit object body"
  (str body-header nonce))

(defrecord Commit [store hash])

(defn commit-object [partial-digest ^String store nonce]
  "Given a commit body, generate the full commit object including hash."
  (let [hash (compute-digest partial-digest nonce)]
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

(defn build-commit [body-header partial-digest nonce]
  (let [str-nonce (pad-nonce (nonce-to-string nonce))
        store (commit-content body-header str-nonce)]
    (commit-object partial-digest store str-nonce)))

(defn work-on-partition [nonces body-header partial-digest difficulty]
  "Given a unit of work (list of nonces), return the first one that satisfies the test, or none"
  (->> nonces
       (map #(build-commit body-header partial-digest %))
       (filter #(within-difficulty % difficulty))
       first))

(defn find-first-commit [body-header partial-digest difficulty]
  (->> nonce-seq
       (partition 1024)
       (pmap #(work-on-partition % body-header partial-digest difficulty))
       (filter #(not (nil? %)))
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
  (str user ": " (inc (Integer/parseInt amount)) "\n"))

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

(defn push-to-master [repo hash]
  (with-sh-dir repo
    (sh "git" "push" "origin" (str hash ":master"))))

(defn find-commit [repo username]
  (let [difficulty (get-difficulty repo)
        _ (update-ledger repo username)
        _ (add-ledger repo)
        tree (write-tree repo)
        parent (get-parent repo)
        timestamp (to-long (now))
        body-header (commit-body-header tree parent timestamp)
        partial-digest (build-partial-digest body-header)]
    (find-first-commit body-header partial-digest difficulty)))

(defn run [repo username]
  ;; TODO fetch master and reset
  (fetch-and-reset repo)
  (let [start (System/currentTimeMillis)]
    (when-let [commit (find-commit repo username)]
      (deflate-commit repo commit)
      (push-to-master repo (:hash commit))
      (let [end (System/currentTimeMillis)]
        (println "Mined a gitcoin!") ; TODO print commit message?
        (println (str "Commit: " commit))
        (println "Time taken" (- end start) "ms"))
      commit)) ;; shell out
  )

(def repo "/tmp/current-round")
(def username "user-j7hi8ian")

(defn start
  ([] (start 15000))
  ([timeout]
     (while true
       (let [attempt (future (run repo username))
             result (deref attempt timeout :timeout)]
         (println result)
         (if (= result :timeout)
           (future-cancel attempt))))))
;; TODO timeout 30 seconds?

(defn flush-chan [chan]
  (async/go
   (while true
     (async/<! chan))))

(defn process-results
  "Processes the results, then closes and flushes the data channel."
  [result-chan data-chan repo]
  (println "Waiting for results")
  (if-let [commit (async/<!! result-chan)]
    (do
      (deflate-commit repo commit)
      (println "Mined a gitcoin!")
      (println (str "Commit: " commit))
      (push-to-master repo (:hash commit))
      (println "process-results killing data-chan")
      (async/close! data-chan))
    (do
      (println "No commit :(")
      (flush-chan data-chan))))

(defn process-data [data-chan result-chan difficulty tree parent timestamp]
  (async/go
   (while true
     (let [nonce (async/<! data-chan)]
       (when nonce
         (let [commit (build-commit tree parent timestamp nonce)]
           (when (within-difficulty commit difficulty)
             (async/>! result-chan commit)
             (println "data-process killing data-chan")
             (async/close! data-chan)
             (async/close! result-chan))))))))

(defn find-commit-async [repo username timeout]
  (let [difficulty (get-difficulty repo)
        _ (update-ledger repo username)
        _ (add-ledger repo)
        tree (write-tree repo)
        parent (get-parent repo)
        timestamp (to-long (now))
        data-chan (async/to-chan nonce-seq)
        result-chan (async/timeout timeout)
        num-goroutines 4
        start (System/currentTimeMillis)]
    (dotimes [i num-goroutines]
      (process-data data-chan result-chan difficulty tree parent timestamp))
    (println num-goroutines "goroutines spun up, total time" (- (System/currentTimeMillis) start) "ms")
    {:result-chan result-chan :data-chan data-chan}))

(defn find-commit-async-test [repo]
  (let [channels (find-commit-async repo username 150000)]
    (async/<!! (:result-chan channels))))

(defn run-async
  ([] (run-async 15000))
  ([timeout] (run-async 15000 repo username))
  ([timeout repo username]
     (let [channels (find-commit-async repo username timeout)]
       (process-results (:result-chan channels) (:data-chan channels)))))

(defn run-async-test [repo]
  (run-async 150000 repo username))

(defn hash-rate [nonce time-in-sec]
  (let [num-hashes (Integer/parseInt nonce 36)]
    (float (/ num-hashes time-in-sec))))
