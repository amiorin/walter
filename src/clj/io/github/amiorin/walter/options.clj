(ns io.github.amiorin.walter.options
  (:require
   [big-config.render :as render]
   [big-config.workflow :as workflow]))

(def ^:private oci {::workflow/params {:provider-compute "oci"
                                       :oci-config-file-profile "DEFAULT"
                                       :oci-subnet-id "ocid1.subnet.oc1.eu-frankfurt-1.aaaaaaaaotya32pihejgi25vrdfnjda3qg52kpsjnd7od5oiqifbsi4rqqma"
                                       :oci-compartment-id "ocid1.tenancy.oc1..aaaaaaaal4wmmpzv2fzkdz2vrfdizywgzjid6dqlgcankrrr7jyydo7ozb3a"
                                       :oci-availability-domain "xTQn:EU-FRANKFURT-1-AD-1"
                                       :oci-display-name "my-ampere-instance"
                                       :oci-shape "VM.Standard.A1.Flex"
                                       :oci-ocpus 1
                                       :oci-memory-in-gbs 6
                                       :oci-boot-volume-size-in-gbs 50
                                       :oci-boot-volume-vpus-per-gb 30
                                       :oci-ssh-authorized-keys "~/.ssh/id_ed25519.pub"}})

(def ^:private s3 {::workflow/params {:provider-backend "s3"
                                      :s3-bucket "tf-state-251213589273-eu-west-1"
                                      :s3-region "eu-west-1"}})

(def walter (merge-with merge oci s3
                        {::render/profile "walter"
                         ::workflow/params {:package "walter"}}))

(def bb walter)

(comment
  (-> bb))
