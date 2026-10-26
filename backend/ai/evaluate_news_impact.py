"""Offline evaluation of labeled, saved Naver results. Never calls any provider."""
import argparse
import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from app.news_impact import POLICY_HASH, normalize_product, evaluate
from app.naver_news import normalize_articles


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases",required=True,type=Path)
    args=parser.parse_args()
    stages=Counter(); directions=Counter(); count=0
    true_positive=false_positive=false_negative=0
    with args.cases.open(encoding="utf-8-sig") as source:
        for number,line in enumerate(source,1):
            if not line.strip(): continue
            if count>=5000: raise SystemExit("At most 5000 labeled cases per run")
            try:
                data=json.loads(line);now=datetime.fromisoformat(data["asOf"])
                expected=data["expectedStage"]
                if now.tzinfo is None or expected not in {"IRRELEVANT","RELATED_ONLY","REFERENCE","EVIDENCE"}: raise ValueError()
                product=data["product"]
                context=normalize_product(product["category"],product["keywords"],product["title"],product.get("productId",""),product.get("maker",""),product.get("brand",""),product.get("intro",""))
                candidates=normalize_articles([data["item"]],now)
                actual=evaluate(candidates[0],context,now) if candidates else {"assessmentStage":"IRRELEVANT","priceImpact":"IRRELEVANT"}
            except (KeyError,TypeError,ValueError,AttributeError):
                raise SystemExit(f"Invalid labeled case at line {number}") from None
            count+=1;stages[(expected,actual["assessmentStage"])]+=1
            predicted=actual["assessmentStage"] in {"REFERENCE","EVIDENCE"}
            positive=expected in {"REFERENCE","EVIDENCE"}
            true_positive+=int(predicted and positive);false_positive+=int(predicted and not positive);false_negative+=int(not predicted and positive)
            if data.get("expectedImpact"): directions[(data["expectedImpact"],actual["priceImpact"])]+=1
    print(json.dumps({"cases":count,"policyHash":POLICY_HASH,
        "selectionPrecision":true_positive/(true_positive+false_positive) if true_positive+false_positive else None,
        "selectionRecall":true_positive/(true_positive+false_negative) if true_positive+false_negative else None,
        "stages":[{"expected":a,"actual":b,"count":n} for (a,b),n in sorted(stages.items())],
        "directions":[{"expected":a,"actual":b,"count":n} for (a,b),n in sorted(directions.items())]},ensure_ascii=False,indent=2))


if __name__=="__main__": main()
