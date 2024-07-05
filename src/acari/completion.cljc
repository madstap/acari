(ns acari.completion
  (:require [acari.platform :as platform]
            [babashka.process :as proc]
            [clojure.string :as str]))

(defmulti script {:arglists '([{:keys [shell] :as opts}])} :shell)

(defn print-script [{:keys [shell] :as opts}]
  (println (script opts)))

(defn args-and-word
  "Given the line and cursor index, returns a map of :acari/args and
  :acari/word, the args up to the cursor and the current word to be
  completed, respectively."
  [line point]
  (let [l (subs line 0 point)
        ;; The first word is the executable.
        words (rest (proc/tokenize l))]
    (if (or (re-find #"\s$" l) (= "" l))
      {:acari/args words, :acari/word ""}
      {:acari/args (or (butlast words) ()), :acari/word (or (last words) "")})))

(defn normalize-completions [completions]
  (-> (if (map? completions) completions {:completions completions})
      (update :completions (partial map #(if (string? %) {:candidate %} %)))))

(defn filter-prefix [prefix completions]
  (cond->> completions
    (not (str/blank? prefix))
    (filter (fn [{:keys [candidate]}]
              (and (str/starts-with? candidate prefix)
                   (not= candidate prefix))))))

(defmulti get-ctx {:arglists '([shell])} identity)

(defmulti emit-completions
  {:arglists '([ctx completions])}
  (fn [{:acari/keys [shell]} _] shell))

;; Maybe this doesn't need to be specific to any shell
(defmethod emit-completions :default [ctx completions]
  (let [{comps :completions} (normalize-completions completions)
        on-complete (or (and (= 1 (count comps))
                             (:on-complete (first comps)))
                        :next)
        filtered (filter-prefix (:acari/word ctx) comps)]
    (cons (name on-complete) (map :candidate filtered))))

(defn print-completions* [shell f]
  (let [ctx (get-ctx shell)]
    (emit-completions ctx (f ctx))))

(defn log-file []
  (platform/getenv "COMP_DEBUG_FILE"))

(defn print-completions [shell f]
  (run! println (print-completions*
                 shell
                 (-> f
                     (platform/wrap-print-errors)
                     (platform/wrap-redirect-out (log-file))))))

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

    # The first line is a directive.
    # Currently only [next | continue]
    if [ ${RESPONSE[0]} = 'next' ]; then
        compopt +o nospace
    fi

    # Remove the directive
    unset RESPONSE[0]

    COMPREPLY=(${RESPONSE[@]})
}
complete -o nospace -F " fn-name " " command-name)))

(defmethod get-ctx "bash" [_]
  (-> (args-and-word (platform/getenv "COMP_LINE")
                     (parse-long (platform/getenv "COMP_POINT")))
      (assoc :acari/shell "bash")))
