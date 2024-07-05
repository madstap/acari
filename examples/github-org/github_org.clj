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
     (let [orgs (gh-get "users" member "orgs")]
       (->> orgs (map :login) (run! println))))

   "completions-script"
   (fn []
     (acari/print-script {:shell :bash
                          :command-name "github_org.clj"
                          :completions-command "github_org.clj print-completions"}))

   "print-completions"
   (fn []
     (acari/print-completions :bash
                              (fn [{[cmd org :as args] :acari/args :as ctx}]
                                (acari/log ctx)
                                (if (empty? args)
                                  (keys commands)
                                  (case cmd
                                    "member-orgs" (if org
                                                    (fetch-members org)
                                                    clj-orgs))))))})

(defn main [[cmd & args]]
  (apply (commands cmd) args))

(when (= *file* (System/getProperty "babashka.file"))
  (main *command-line-args*))
