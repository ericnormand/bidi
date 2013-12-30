;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns bidi.bidi-test
  (:require [clojure.test :refer :all]
            [bidi.bidi :refer :all]
            [ring.mock.request :refer :all]))

(deftest matching-routes-test
  (testing "misc-routes"
    (is (= (match-route "/blog/foo" ["/blog/foo" 'foo])
           {:handler 'foo}))

    (is (= (match-route "/blog/bar/abc"
                        ["/blog" [["/foo" 'foo]
                                  ["/bar" [["/abc" :bar]]]]])
           {:handler :bar}))

    (is (= (match-route "/blog/bar/articles/123/index.html"
                        ["/blog" [["/foo" 'foo]
                                  [["/bar" :path] :bar]]])
           {:handler :bar :params {:path "/articles/123/index.html"}}))

    ;; The example in the README, so make sure it passes!
    (is (= (match-route "/blog/bar/articles/123/index.html"
                        ["/blog" [["/index.html" 'index]
                                  [["/bar/articles/" :artid "/index.html"] 'article]]])
           {:handler 'article :params {:artid "123"}}))

    (is (= (match-route "/blog/bar/articles/123/index.html"
                        ["/blog" [["/foo" 'foo]
                                  [["/bar/articles/" :artid "/index.html"] 'bar]]])
           {:handler 'bar :params {:artid "123"}}))

    (is (= (match-route "/blog/articles/123/index.html"
                        ["/blog" [[["/articles/" :id "/index.html"] 'foo]
                                  ["/text" 'bar]]])
           {:handler 'foo :params {:id "123"}}))

    (testing "regex"
      (is (= (match-route "/blog/articles/123/index.html"
                          ["/blog" [[["/articles/" [#"\d+" :id] "/index.html"] 'foo]
                                    ["/text" 'bar]]])
             {:handler 'foo :params {:id "123"}}))
      (is (= (match-route "/blog/articles/123a/index.html"
                          ["/blog" [[["/articles/" [#"\d+" :id] "/index.html"] 'foo]
                                    ["/text" 'bar]]])
             nil))
      (is (= (match-route "/blog/articles/123abc/index.html"
                          ["/blog" [[["/articles/" [#"\d+" :id] [#"\p{Lower}+" :a] "/index.html"] 'foo]
                                    ["/text" 'bar]]])
             {:handler 'foo :params {:id "123" :a "abc"}}))

      (is (= (match-route "/blog/articles/123abc/index.html"
                          [#"/bl\p{Lower}{2}+" [[["/articles/" [#"\d+" :id] [#"\p{Lower}+" :a] "/index.html"] 'foo]
                                                ["/text" 'bar]]])
             {:handler 'foo :params {:id "123" :a "abc"}})))

    (testing "boolean patterns"
      (is (= (match-route "/any" [true :index]) {:handler :index}))
      (is (= (match-route "/any" [false :index]) nil)))))

(deftest unmatching-routes-test
  (let [routes ["/"
                [["blog"
                  [["/index.html" 'blog-index]
                   [["/article/" :id ".html"] 'blog-article-handler]
                   [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                 [["images/" :path] 'image-handler]]]]

    (testing "unmatching"

      (is
       (= (path-for routes 'blog-index)
          "/blog/index.html"))
      (is
       (= (path-for routes 'blog-article-handler :id 1239)
          "/blog/article/1239.html"))
      (is
       ;; If not all the parameters are specified we expect an error to be thrown
       (thrown? clojure.lang.ExceptionInfo (path-for routes 'archive-handler :id 1239)
                "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes 'archive-handler :id 1239 :page "section")
          "/blog/archive/1239/section.html"))
      (is
       (= (path-for routes 'image-handler :path "")
          "/images/"))

      (is
       (= (path-for routes 'image-handler :path "123.png")
          "/images/123.png")))

    (testing "unmatching with constraints"

      (let [routes ["/" [["blog"
                          [[:get [[["/index"] :index]]]
                           [{:request-method :post :server-name "juxt.pro"}
                            [[["/articles/" :artid] :new-article-handler]]]]]]]]
        (is (= (path-for routes :index)
               "/blog/index"))
        (is (= (path-for routes :new-article-handler :artid 10)
               "/blog/articles/10"))))
    (testing "unmatching with regexes"
      (let [routes
            ["/blog" [[["/articles/" [#"\d+" :id] [#"\p{Lower}+" :a] "/index.html"] 'foo]
                      ["/text" 'bar]]]]
        (is (= (path-for routes 'foo :id "123" :a "abc")
               "/blog/articles/123abc/index.html"))
        ))))


(deftest unmatching-routes-with-anonymous-fns-test
  (testing "unmatching when routes contains a ref to anonymous function(s) should not throw exception"
    (let [routes
          ["/blog" [["/index.html" (fn [req] {:status 200 :body "Index"})]
                    ["/list" 'list-blogs]
                    ["/temp.html" :temp-html]]]]
      (is (= (path-for routes 'list-blogs)
             "/blog/list"))
      (is (= (path-for routes :temp-html)
             "/blog/temp.html")))))


(deftest make-handler-test

  (testing "routes"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [["/index.html" (fn [req] {:status 200 :body "Index"})]
                            [["/article/" :id ".html"] 'blog-article-handler]
                            [["/archive/" :id "/" :page ".html"] 'archive-handler]]]
                          ["images/" 'image-handler]]])]
      (is (= (handler (request :get "/blog/index.html"))
             {:status 200 :body "Index"}))))

  (testing "method constraints"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [[:get [["/index.html" (fn [req] {:status 200 :body "Index"})]]]
                            [:post [["/zip" (fn [req] {:status 201 :body "Created"})]]]]
                           ]]])]

      (is handler)
      (is (= (handler (request :get "/blog/index.html")) {:status 200 :body "Index"}))
      (is (nil? (handler (request :post "/blog/index.html"))))
      (is (= (handler (request :post "/blog/zip")) {:status 201 :body "Created"}))
      (is (nil? (handler (request :get "/blog/zip"))))))

  (testing "other request constraints"

    (let [handler
          (make-handler ["/"
                         [["blog"
                           [[:get
                             [["/index"
                               (fn [req] {:status 200 :body "Index"})]
                              [["/article/" :artid "/article.html"]
                               (fn [req] {:status 200 :body (get-in req [:route-params :artid])})]
                              ]]
                            [{:request-method :post :server-name "juxt.pro"}
                             [["/zip"
                               (fn [req] {:status 201 :body "Created"})]]]]]]])]

      (is handler)
      (is (nil? (handler (request :post "/blog/zip"))))
      (is (= (handler (request :post "http://juxt.pro/blog/zip"))
             {:status 201 :body "Created"}))
      (is (nil? (handler (request :post "/blog/zip"))))
      (testing "artid makes it into :route-params")
      (is (= (handler (request :get "/blog/article/123/article.html"))
             {:status 200 :body "123"})))))

(deftest redirect-test

  (let [content-handler (fn [req] {:status 200 :body "Some content"})
        routes ["/articles"
                [["/new" content-handler]
                 ["/old" (->Redirect 307 content-handler)]]]
        handler (make-handler routes)]
    (is (= (handler (request :get "/articles/old"))
           {:status 307, :headers {"Location" "/articles/new"}, :body ""} ))))


(deftest wrap-middleware-test

  (let [wrapper (fn [h] (fn [req] (assoc (h req) :wrapper :evidence)))
        handler (fn [req] {:status 200 :body "Test"})]
    (is (= ((:handler (match-route "/index.html" ["/index.html" (->WrapMiddleware handler wrapper)]))
            {:uri "/index.html"})
           {:wrapper :evidence :status 200 :body "Test"}))

    (is (= (path-for ["/index.html" (->WrapMiddleware handler wrapper)] handler) "/index.html"))
    (is (= (path-for ["/index.html" handler] handler) "/index.html"))))


(deftest wrap-alternates-test

  (let [routes [(->Alternates ["/index.html" "/index"]) :index]]
    (is (= (match-route "/index.html" routes) {:handler :index}))
    (is (= (match-route "/index" routes) {:handler :index}))
    (is (=(path-for routes :index) "/index.html")) ; first is the canonical one
    ))
