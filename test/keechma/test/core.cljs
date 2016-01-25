(ns keechma.test.core
  (:require [doo.runner :refer-macros [doo-tests]]
            [cljs.test :as test]
            [keechma.test.app-state]
            [keechma.test.router]
            [keechma.test.edb]
            [keechma.test.controller]
            [keechma.test.controller-manager]
            [keechma.test.ui-component]))

(doo-tests 'keechma.test.app-state
           'keechma.test.router
           'keechma.test.edb
           'keechma.test.controller
           'keechma.test.controller-manager
           'keechma.test.ui-component
          )
