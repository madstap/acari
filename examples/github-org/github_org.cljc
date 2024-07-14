#!/usr/bin/env bb

(ns github-org
  (:require [acari.completion :as acari]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn gh-get [& path]
  (-> (http/get (str/join "/" (cons "https://api.github.com" path)))
      :body
      (json/parse-string true)))

(defn fetch-members [org]
  (let [members (gh-get "orgs" org "members")]
    (map :login members)))

(def clj-orgs ["clj-commons" "clj-br" "scicloj"])

(def commands
  {"member-orgs"
   (fn [_org member]
     (->> (gh-get "users" member "orgs") (map :login) (run! println)))

   ;; Subcommand to print the completion shell script
   "completions-script"
   (fn [shell]
     (acari/print-script
      {:shell shell
       ;; The name of the command to be completed
       :command-name "github_org.cljc"
       ;; The command the shell script will invoke to get completions
       :completions-command (str "github_org.cljc print-completions " shell)}))

   ;; The shell script will invoke this command to get completions
   "print-completions"
   (fn [shell]
     (acari/print-completions
      shell
      ;; This function receives a ctx and returns a seqable of strings
      (fn [{[cmd org _member :as args] :acari/args
            _word :acari/word
            :as ctx}]
        ;; Anything that's printed is appended to COMP_DEBUG_FILE (if set)
        (prn ctx)
        (case cmd
          nil (keys commands)
          "member-orgs" (if org
                          (fetch-members org)
                          clj-orgs)
          "completions-script" (when (= 1 (count args))
                                 (acari/supported-shells))
          ;; Uncaught exceptions are printed (ie. appended to COMP_DEBUG_FILE)
          (throw (ex-info "Not found" {}))))))})

(when #?(:bb (= *file* (System/getProperty "babashka.file"))
         :org.babashka/nbb (= *file* (nbb.core/invoked-file))
         :default false)
  (let [[cmd & args] *command-line-args*]
    (apply (commands cmd) args)))
