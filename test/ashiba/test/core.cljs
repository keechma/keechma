(ns ashiba.test.core
  (:require [doo.runner :refer-macros [doo-tests]]
            [cljs.test :as test]
            [ashiba.test.app-state]
            [ashiba.test.router]
            [ashiba.test.edb]
            [ashiba.test.controller]
            [ashiba.test.controller-manager]
            [ashiba.test.ui-component]))

(doo-tests 'ashiba.test.app-state
           'ashiba.test.router
           'ashiba.test.edb
           'ashiba.test.controller
           'ashiba.test.controller-manager
           'ashiba.test.ui-component
          )
