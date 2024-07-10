(ns acari.platform
  (:require [clojure.string :as str]
            ["node:fs" :as fs]))

(defn getenv [env-var]
  (some #(when (= env-var (first %)) (second %))
        (.entries js/Object (.-env js/process))))

;; TODO: Additionally do this? Or do this instead?
;; https://stackoverflow.com/a/35542360/3680995
;; var access = fs.createWriteStream('/var/log/node/api.access.log');
;; process.stdout.write = process.stderr.write = access.write.bind(access);
(defn wrap-redirect-out [f out]
  (fn [ctx]
    (let [writer (some-> out fs/createWriteStream)]
      (binding [*print-newline* true
                *print-fn* #(some-> writer (.write %))]
        (try (f ctx)
             (finally (some-> writer .close)))))))

;; TODO: Additionally do this?
;; process.on('uncaughtException', function(err) {
;;   console.error((err && err.stack) ? err.stack : err);
;; });
(defn wrap-print-errors [f]
  (fn [ctx]
    (try (f ctx)
         (catch :default e
             (println e)))))

(defn tokenize [s]
  ;; TODO: This isn't good enough, we should probably do this on the shell side.
  (str/split s #"\s+"))
