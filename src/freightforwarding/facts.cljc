(ns freightforwarding.facts
  "Per-jurisdiction freight-forwarding + customs-brokerage regulatory
  catalog -- the spec-basis table `freightforwarding.governor` checks
  every proposal against ('is this shipment's jurisdiction one whose
  forwarding/brokerage regime this actor actually knows, or is the
  advisor coordinating in the dark?').

  WHY THIS ACTOR NEEDS IT. Freight forwarding and customs brokerage are
  LICENSED activities almost everywhere, and the licence is what
  separates a coordination act from an unlawful one. This actor
  deliberately does not finalize customs clearances (see the governor's
  closed op-allowlist and its finalize-clearance scope exclusion), but
  it does create official coordination records against a jurisdiction --
  and it should not create one for a jurisdiction whose regime it has no
  registered basis for. `nil` here means NO spec-basis, full stop: the
  advisor must not invent one, and the governor holds if it tries.

  Each entry is a REAL jurisdiction with a REAL licensing regime:

    JPN  貨物利用運送事業法 (平成元年法律第82号, 国土交通省) for the
         forwarding side and 通関業法 (昭和42年法律第122号, 財務省
         関税局/税関) for the brokerage side. Both law IDs below were
         resolved against the e-Gov law API and the returned
         <LawTitle> confirmed to be exactly those two statutes.
    USA  Ocean Transportation Intermediary licensing by the Federal
         Maritime Commission, and customs broker licensing by CBP under
         19 CFR Part 111.
    EUR  Union Customs Code, Regulation (EU) No 952/2013, with the
         Authorised Economic Operator programme as the trusted-trader
         layer.
    GBR  HMRC's customs-intermediary regime (post-UCC divergence).
    KOR  관세법 Customs Act, administered by 관세청 Korea Customs Service.

  HONESTY NOTE. Every url below was fetched and returned 2xx on
  2026-08-06, and `:provenance` records that and nothing stronger --
  reachability of the publisher's page, NOT an extraction of the
  instrument's full text, and NOT a claim about any specific article or
  section number. The two JPN law IDs are the exception: their titles
  were read back from the e-Gov API and are asserted as verified.

  Coverage is reported HONESTLY (see `coverage`) -- five jurisdictions
  is a starting catalog, not the world."
  (:require [clojure.string :as str]))

(def catalog
  "iso3 -> requirement map.

  `:required-evidence` is the licensing evidence a forwarder/broker
  must hold before it may lawfully coordinate in that jurisdiction:
  a forwarding registration, a customs-brokerage licence, and the
  financial security (bond / guarantee / surety) the regime attaches to
  it. All three appear in every regime below in some form."
  {"JPN"
   {:name "JPN"
    :owner-authority "国土交通省 (貨物利用運送) / 財務省関税局・税関 (通関業)"
    :legal-basis "貨物利用運送事業法 (平成元年法律第82号) および 通関業法 (昭和42年法律第122号)"
    :forwarder-regime "第一種/第二種貨物利用運送事業の登録・許可"
    :broker-regime "通関業の許可 + 通関士の設置"
    :required-evidence #{:forwarder-registration :customs-broker-licence :financial-security}
    :sources ["https://elaws.e-gov.go.jp/document?lawid=401AC0000000082"
              "https://elaws.e-gov.go.jp/document?lawid=342AC0000000122"
              "https://www.customs.go.jp/tsukan/index.htm"
              "https://www.mlit.go.jp/jidosha/jidosha_tk2_000009.html"]
    :provenance (str "URL reachability verified 2026-08-06 (HTTP 2xx). "
                     "加えて e-Gov 法令 API で 401AC0000000082 = 貨物利用運送事業法、"
                     "342AC0000000122 = 通関業法 であることを LawTitle で確認済み。"
                     "条文本文の抽出は行っていない。")}

   "USA"
   {:name "USA"
    :owner-authority "Federal Maritime Commission (FMC) / U.S. Customs and Border Protection (CBP)"
    :legal-basis "19 CFR Part 111 (Customs Brokers); FMC Ocean Transportation Intermediary licensing"
    :forwarder-regime "OTI licence (ocean freight forwarder / NVOCC)"
    :broker-regime "Customs broker licence under 19 CFR 111"
    :required-evidence #{:forwarder-registration :customs-broker-licence :financial-security}
    :sources ["https://www.fmc.gov/resources-services/ocean-transportation-intermediaries/"
              "https://www.cbp.gov/trade/programs-administration/customs-brokers"
              "https://www.ecfr.gov/current/title-19/chapter-I/part-111"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 規則本文の抽出は行っていない。"}

   "EUR"
   {:name "EUR"
    :owner-authority "European Commission (Taxation and Customs Union)"
    :legal-basis "Regulation (EU) No 952/2013 laying down the Union Customs Code"
    :forwarder-regime "Customs representation (direct / indirect) under the UCC"
    :broker-regime "Customs representative; AEO for the trusted-trader layer"
    :required-evidence #{:forwarder-registration :customs-broker-licence :financial-security}
    :sources ["https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32013R0952"
              "https://taxation-customs.ec.europa.eu/customs-4/aeo-authorised-economic-operator_en"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}

   "GBR"
   {:name "GBR"
    :owner-authority "HM Revenue & Customs (HMRC)"
    :legal-basis "UK customs-intermediary regime (Taxation (Cross-border Trade) Act 2018 framework)"
    :forwarder-regime "Freight forwarder acting as a customs intermediary"
    :broker-regime "Direct / indirect representation appointed to deal with customs"
    :required-evidence #{:forwarder-registration :customs-broker-licence :financial-security}
    :sources ["https://www.gov.uk/guidance/appoint-someone-to-deal-with-customs-on-your-behalf"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}

   "KOR"
   {:name "KOR"
    :owner-authority "관세청 Korea Customs Service"
    :legal-basis "관세법 Customs Act"
    :forwarder-regime "국제물류주선업 international freight forwarding registration"
    :broker-regime "관세사 licensed customs broker"
    :required-evidence #{:forwarder-registration :customs-broker-licence :financial-security}
    :sources ["https://www.law.go.kr/LSW/eng/engLsSc.do?menuId=2&section=lawNm&query=CUSTOMS+ACT"]
    :provenance "URL reachability verified 2026-08-06 (HTTP 2xx). 条文本文の抽出は行っていない。"}})

(defn jurisdiction
  "The catalog entry for `iso3`, or nil. nil means NO spec-basis exists
  for that jurisdiction -- the advisor must not invent one."
  [iso3]
  (get catalog iso3))

(defn spec-basis-known?
  "True iff this jurisdiction has an official spec-basis on file.
  A blank/nil jurisdiction is never known."
  [iso3]
  (boolean (and (string? iso3)
                (not (str/blank? iso3))
                (some? (jurisdiction iso3)))))

(defn required-evidence
  "The licensing evidence set this jurisdiction demands, or nil when
  the jurisdiction is unknown."
  [iso3]
  (:required-evidence (jurisdiction iso3)))

(defn required-evidence-satisfied?
  "True iff `checklist` (a set of satisfied evidence keys) covers every
  item this jurisdiction requires. An UNKNOWN jurisdiction is never
  satisfied -- absence of a rule is not permission."
  [iso3 checklist]
  (let [req (required-evidence iso3)]
    (boolean (and req (every? (set checklist) req)))))

(defn sources
  "The citation list for a jurisdiction (empty when unknown). This is
  what an advisor should put in its `:cites`."
  [iso3]
  (vec (:sources (jurisdiction iso3))))

(defn coverage
  "Honest coverage report. Anything outside `:jurisdictions` has NO
  spec-basis, and this catalog covers five jurisdictions, not the world."
  []
  {:jurisdictions (vec (sort (keys catalog)))
   :count (count catalog)
   :evidence-keys #{:forwarder-registration :customs-broker-licence :financial-security}
   :note (str "掲載 URL は 2026-08-06 に 2xx を確認した publisher ページであり、条文全文の抽出ではない。"
              "JPN の 2 法令 ID のみ e-Gov 法令 API の LawTitle で法令名まで確認済み。"
              "ここに無い法域は spec-basis が無いのであって、要件が無いのではない。")})
