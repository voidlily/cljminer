# cljminer

A Gitcoin miner written for Stripe CTF. Since this was written in Clojure, performance isn't as good as an equivalent miner written in C. On my machine, I get about 400khash/s running this miner

# What are Gitcoins?

(Description from [Stripe CTF](https://stripe-ctf.com/levels/1))

Gitcoin

Cryptocurrencies are all the rage these days. Thus, today we're proud to announce the release of a new one, entitled Gitcoin.

It's easy to start a new Gitcoin instance: you start with a Git repository containing a LEDGER.txt file, which represents the starting balances (denoted in the form username: <balance>). You then transact by committing balance updates to the repository. A valid ledger might look like this:

Private Gitcoin ledger
==============
siddarth: 52
ludwig: 23
gdb: 41
woodrow: 151

There's a twist, however: in order to push a new commit, that commit's SHA1 must be lexicographically less than the value contained in the repository's difficulty.txt file. For instance, a commit in a Gitcoin blockchain with difficulty 00005 might look something like the following:

commit 00004216ba61aecaafb11135ee43b1674855d6ff7
Author: Alyssa P Hacker <alyssa@example.com>
Date:   Wed Jan 22 14:10:15 2014 -0800

    Give myself a Gitcoin

    nonce: tahf8buC

diff --git a/LEDGER.txt b/LEDGER.txt
index 3890681..41980b2 100644
--- a/LEDGER.txt
+++ b/LEDGER.txt
@@ -7,3 +7,4 @@ andy: 30
 carl: 12
 nelhage: 45
 jorge: 30
+username: 1

However, it wouldn't be valid if difficulty.txt contained 00003 or 000001.

The git repository's history thus forms a blockchain with a proof-of-work for each block, just like in Bitcoin. Of course, unlike Bitcoin, Gitcoin just uses Git and doesn't require a custom client.

Gitcoins are mined by adding yourself to the LEDGER.txt with 1 Gitcoin (or incrementing your entry if already present) in a commit with an allowable SHA1. For simplicity, the ledger is maintained centrally and never rolled back (though, there's no inherent reason we couldn't decentralize Gitcoin, since it's built off of the same foundation as Bitcoin).

## Usage

```
(use 'cljminer.core)
(def repo "/path/to/local/repo")
(def username "my-username")
(start)
```

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
