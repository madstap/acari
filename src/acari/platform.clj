(ns acari.platform)

(defn getenv [env-var]
  (System/getenv env-var))

(defn append-file-line [file s]
  (spit file (str s "\n") :append true))
