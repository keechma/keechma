(ns syntest.test)

(defn prepare-actions [args actions]
  (into [] (map (fn [f] `(fn ~args ~f)) actions)))

(defn run-async [args] args)

(defmacro synasync [args & actions]
  `(syntest.test.run-async ~(prepare-actions args (or actions `()))))
