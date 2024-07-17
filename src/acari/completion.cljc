(ns acari.completion
  (:require [clojure.string :as str]
            #?@(:clj [[babashka.process :as proc]
                      [babashka.fs :as fs]
                      [clojure.java.io :as io]]
                :cljs [["node:fs" :as node-fs]
                       ["node:path" :as node-path]]))
  #?(:clj (:import (java.io Writer))))

#?(:cljs
   (defn oget [obj k]
     (some #(when (= k (first %)) (second %))
           (.entries js/Object obj))))

(defn getenv [env-var]
  #?(:clj (System/getenv env-var)
     :cljs (oget (.-env js/process) env-var)))

;; TODO: Additionally do this? (And the equivalent java.)
;; process.on('uncaughtException', function(err) {
;;   console.error((err && err.stack) ? err.stack : err);
;; });
(defn wrap-print-errors [f]
  (fn [ctx]
    (try (f ctx)
         (catch #?(:clj Throwable :cljs :default) e
           (println e)))))

#?(:cljs
   (defn wrap-handler [f out]
     (fn [ctx]
       (let [writer (some-> out (node-fs/createWriteStream #js {:flags "a+"}))
             write! (or (some-> writer .-write (.bind writer))
                        (fn [_]))
             old-out-write (.-write js/process.stdout)
             old-err-write (.-write js/process.stderr)]
         ;; https://stackoverflow.com/a/35542360/3680995
         (set! (.-write js/process.stdout) write!)
         (set! (.-write js/process.stderr) write!)
         (-> (.resolve js/Promise
                       (binding [*print-newline* true
                                 *print-fn* #(some-> writer (.write %))]
                         ((wrap-print-errors f) ctx)))
             (.catch (fn [err]
                       (write! (str err "\n"))
                       nil))
             (.then (fn [ret]
                      (set! (.-write js/process.stdout) old-out-write)
                      (set! (.-write js/process.stderr) old-err-write)
                      (some-> writer .close)
                      ret)))))))

#?(:clj
   (defn wrap-redirect-out [f out]
     (fn [ctx]
       (let [writer (if (string? out)
                      (io/writer out :append true)
                      (Writer/nullWriter))]
         (binding [*out* writer, *err* writer]
           (f ctx))))))

#?(:clj
   (defn wrap-handler [f out]
     (-> f
         (wrap-print-errors)
         (wrap-redirect-out out))))

#?(:cljs
   (defn sleep [ms]
     (js/Promise. #(js/setTimeout % ms))))

(defn tokenize [s]
  #?(:clj (proc/tokenize s)
     ;; TODO: This isn't good enough, we should probably do this on the shell side.
     :cljs (str/split s #"\s+")))

(defmulti script {:arglists '([{:keys [shell] :as opts}])} :shell)

(defn supported-shells []
  (keys (methods script)))

(defn print-script [{:keys [shell] :as opts}]
  (println (script opts)))

(defn args-and-word
  "Given the line and cursor index, returns a map of :acari/args and
  :acari/word, the args up to the cursor and the current word to be
  completed, respectively."
  [line point]
  (let [l (subs line 0 point)
        ;; The first word is the executable.
        words (rest (tokenize l))
        [args word] (if (or (re-find #"\s$" l) (= "" l))
                      [(vec words) ""]
                      [(vec (butlast words)) (or (last words) "")])]
    {:acari/line line, :acari/point point, :acari/args args, :acari/word word}))

(defn normalize-completions [completions]
  (-> (if (map? completions) completions {:completions completions})
      (update :completions (partial map #(if (string? %) {:candidate %} %)))))

(defn filter-prefix [prefix completions]
  (cond->> completions
    (not (str/blank? prefix))
    (filter (fn [{:keys [candidate]}]
              (and (str/starts-with? candidate prefix)
                   (not= candidate prefix))))))

(defmulti get-ctx* {:arglists '([shell])} identity)

(defn get-ctx [shell]
  (assoc (get-ctx* shell) :acari/shell shell))

(defn emit-completions [ctx completions]
  (let [{comps :completions} (normalize-completions completions)
        on-complete (or (and (= 1 (count comps))
                             (:on-complete (first comps)))
                        :next)
        filtered (filter-prefix (:acari/word ctx) comps)]
    (cons (name on-complete) (map :candidate filtered))))

(defn log-file []
  (getenv "COMP_DEBUG_FILE"))

(defn print-completions [shell f]
  (let [handler (wrap-handler f (log-file))
        ctx (get-ctx shell)]
    #?(:default (run! println (emit-completions ctx (handler ctx)))
       :cljs (-> (handler ctx)
                 (.then
                  (fn [completions]
                    (run! js/console.log (emit-completions ctx completions))))))))

(def file-sep
  #?(:clj fs/file-separator
     :cljs node-path/sep))

(defn append-file-sep [dir]
  (cond-> dir (not (str/ends-with? dir file-sep)) (str file-sep)))

(defn split-file-sep [file]
  (str/split file (re-pattern file-sep)))

(defn ls [dir]
  #?(:clj (map #(str/replace (str %) #"^\./" "") ; TODO: Windows
               (fs/list-dir dir))
     :cljs (map #(str (when (not= dir ".") (append-file-sep dir)) %)
                (node-fs/readdirSync dir))))

(defn extension [file]
  #?(:clj (fs/extension file)
     :cljs (when-some [ext (not-empty (node-path/extname file))]
             (subs ext 1 (count ext)))))

(defn directory? [file]
  #?(:clj (fs/directory? file)
     :cljs (and (node-fs/existsSync file)
                (-> (node-fs/lstatSync file) .isDirectory))))

(defn source-dir [dir word]
  (let [non-blank-dir? (and (not (str/blank? dir)) (not= "." dir))
        file-sep-in-word? (str/includes? word file-sep)]
    (str (when non-blank-dir? dir)
         (when (and non-blank-dir? file-sep-in-word?) file-sep)
         (when file-sep-in-word?
           (str (cond->> (split-file-sep word)
                  (not (str/ends-with? word file-sep)) butlast
                  true (str/join file-sep))
                file-sep)))))

(defn file [{:acari/keys [word] :as _ctx} & {:keys [ext dir] :or {dir "."}}]
  (let [curr-dir (source-dir dir word)
        ext* (if (string? ext) #{ext} (set ext))]
    (->> (cond->> (ls curr-dir)
           ext (filter (some-fn directory? #(contains? ext* (extension %)))))
         (map #(let [dir? (directory? %)]
                 {:candidate (str % (when dir? "/"))
                  :on-complete (if dir? :continue :next)})))))

(defn dir [ctx & {:as opts}]
  (->> (file ctx opts) (filter #(directory? (:candidate %)))))

(comment

  (file {:acari/word "examples/"} {})
  (file {:acari/word "./examples/"} {})
  (file {:acari/word "./examples/github-org/"})

  )

;; Bash

(defn sanitize-bash-fn-name [s]
  ;; https://stackoverflow.com/questions/28114999/what-are-the-rules-for-valid-identifiers-e-g-functions-vars-etc-in-bash
  (-> s
      (str/replace "!" "bang")
      (str/replace "?" "qmark")
      (str/replace "-" "_")))

(defn bash-fn-name [command-name]
  (str "_" (sanitize-bash-fn-name command-name) "_completions"))

(defmethod script "bash"
  [{:keys [command-name completions-command]}]
  (let [fn-name (bash-fn-name command-name)]
    (str "function " fn-name "()
{
    export COMP_LINE=${COMP_LINE}
    export COMP_POINT=$COMP_POINT

    RESPONSE=($(" completions-command "))

    # The first line is a comma separated list of directives.
    # Currently only on-complete, which can be [next | continue].

    # We replace any commas with spaces, the now space separated list is then
    # read as an array.
    DIRECTIVES=(${RESPONSE[0]//,/ })
    if [ ${DIRECTIVES[0]} = 'next' ]; then
        compopt +o nospace
    fi

    # Remove the directives
    unset RESPONSE[0]

    COMPREPLY=(${RESPONSE[@]})
}
complete -o nospace -F " fn-name " " command-name)))

(defmethod get-ctx* "bash" [_]
  (args-and-word (getenv "COMP_LINE") (parse-long (getenv "COMP_POINT"))))
