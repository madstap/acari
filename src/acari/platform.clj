(ns acari.platform
  (:require [babashka.process :as proc]
            [clojure.java.io :as io])
  (:import (java.io Writer)))

(defn getenv [env-var]
  (System/getenv env-var))

(defn wrap-redirect-out [f out]
  (fn [ctx]
    (let [writer (if (string? out)
                   (io/writer out :append true)
                   (Writer/nullWriter))]
      (binding [*out* writer, *err* writer]
        (f ctx)))))

(defn wrap-print-errors [f]
  (fn [ctx]
    (try (f ctx)
         (catch Throwable e
           (println e)))))

(defn tokenize [s]
  (proc/tokenize s))
