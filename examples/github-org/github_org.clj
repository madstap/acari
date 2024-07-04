#!/usr/bin/env bb
(ns github-org
  (:require [acari.completion :as acari]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

(defn fetch-members [org]
  (-> (http/get (str "https://api.github.com/orgs/" org "/members"))
      :body
      (json/parse-string true)))

(defn fetch-member-names [org]
  (->> (fetch-members org) (map :login)))

(comment
  (fetch-member-names "clj-br")

  (fetch-member-names "scicloj")
  )

(defn main [[command org member]]
  (case command
    "print-member" (println (str org "/" member))

    "completions-script"
    (acari/print-script {:shell :bash
                         :command-name "github_org.clj"
                         :completions-command "github_org.clj print-completions"})

    "print-completions"
    (acari/print-completions :bash
                             (fn [{[_ org :as  acari-args] :acari/args :as ctx}]
                               (acari/log ctx)
                               (case (count acari-args)
                                 0 ["print-member" "completions-script"]
                                 1 ["clj-br" "scicloj"]
                                 2 (fetch-member-names org))))))

(when (= *file* (System/getProperty "babashka.file"))
  (main *command-line-args*))
