(ns ashiba.ui-component)

(defprotocol IUIComponent  
  (url [this params])
  (subscription [this name])
  (component [this name]))

(defrecord UIComponent [topic components subscriptions render]
  IUIComponent
  (url [this params])
  (subscription [this name])
  (component [this name]))
