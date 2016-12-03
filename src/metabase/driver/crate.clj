(ns metabase.driver.crate
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [honeysql.core :as hsql]
            [metabase.driver :as driver]
            [metabase.driver.crate.util :as crate-util]
            [metabase.driver.generic-sql :as sql]
            [metabase.util :as u]
            [clojure.tools.logging :as log])
  (:import (java.sql DatabaseMetaData)))

(def ^:private ^:const column->base-type
  "Map of Crate column types -> Field base types
   Crate data types -> https://crate.io/docs/reference/sql/data_types.html"
  {:integer         :type/Integer
   :string          :type/Text
   :boolean         :type/Boolean
   :byte            :type/Integer
   :short           :type/Integer
   :long            :type/BigInteger
   :float           :type/Float
   :double          :type/Float
   :ip              :type/*
   :timestamp       :type/DateTime
   :geo_shape       :type/Dictionary
   :geo_point       :type/Array
   :object          :type/Dictionary
   :array           :type/Array
   :object_array    :type/Array
   :string_array    :type/Array
   :integer_array   :type/Array
   :float_array     :type/Array
   :boolean_array   :type/Array
   :byte_array      :type/Array
   :timestamp_array :type/Array
   :short_array     :type/Array
   :long_array      :type/Array
   :double_array    :type/Array
   :ip_array        :type/Array
   :geo_shape_array :type/Array
   :geo_point_array :type/Array})


(def ^:private ^:const now (hsql/call :current_timestamp 3))

(defn- crate-spec
  [{:keys [hosts]
    :as   opts}]
  (merge {:classname   "io.crate.client.jdbc.CrateDriver" ; must be in classpath
          :subprotocol "crate"
          :subname     (str "//" hosts "/")}
         (dissoc opts :hosts)))

(defn- can-connect? [details]
  (let [connection-spec (crate-spec details)]
    (= 1 (first (vals (first (jdbc/query connection-spec ["select 1"])))))))

(defn- string-length-fn [field-key]
  (hsql/call :char_length field-key))

(defn- describe-table-fields
  [database, driver, {:keys [schema name]}]
  (set (doseq [{column_name :column_name, type_name :type_name}
             (jdbc/query
                 (sql/db->jdbc-connection-spec database)
                 [(format "select column_name, data_type as type_name
                  from information_schema.columns
                  where table_name like '%s' and table_schema like '%s'" name schema)])]
          (merge {:name      column_name
                 :custom    {:column-type type_name}
                 :base-type (or (column->base-type driver (keyword type_name))
                                (do (log/warn (format "Don't know how to map column type '%s' to a Field base_type, falling back to :type/*." type_name))
                                    :type/*))}))))

(defn- add-table-pks
  [^DatabaseMetaData metadata, table]
  (let [pks (->> (.getPrimaryKeys metadata nil nil (:name table))
                 jdbc/result-set-seq
                 (mapv :column_name)
                 set)]
    (update table :fields (fn [fields]
                            (set (for [field fields]
                                   (if-not (contains? pks (:name field))
                                     field
                                     (assoc field :pk? true))))))))

(defn- describe-table [driver database table]
  (sql/with-metadata [metadata driver database]
                 (->> (assoc (select-keys table [:name :schema]) :fields (describe-table-fields database driver table))
                      ;; find PKs and mark them
                      (add-table-pks metadata))))

(defn- field->alias [field]
  (str \" (name field) \"))

(defrecord CrateDriver []
  clojure.lang.Named
  (getName [_] "Crate"))

(u/strict-extend CrateDriver
  driver/IDriver
  (merge (sql/IDriverSQLDefaultsMixin)
         {:can-connect?   (u/drop-first-arg can-connect?)
          :date-interval  crate-util/date-interval
          :describe-table describe-table
          :details-fields (constantly [{:name         "hosts"
                                        :display-name "Hosts"
                                        :default      "localhost:5432"}])
          :features       (comp (u/rpartial set/difference #{:foreign-keys}) sql/features)})
  sql/ISQLDriver
  (merge (sql/ISQLDriverDefaultsMixin)
         {:connection-details->spec  (u/drop-first-arg crate-spec)
          :column->base-type         (u/drop-first-arg column->base-type)
          :string-length-fn          (u/drop-first-arg string-length-fn)
          :date                      crate-util/date
          :quote-style               :ansi
          :field->alias              (u/drop-first-arg field->alias)
          :unix-timestamp->timestamp crate-util/unix-timestamp->timestamp
          :current-datetime-fn       (constantly now)}))


(driver/register-driver! :crate (CrateDriver.))
