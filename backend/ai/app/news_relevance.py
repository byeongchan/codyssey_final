"""Match supplied category aliases without requiring a particular product model."""

import re


def category_hits(text, keywords):
    lowered = text.lower()
    compact = re.sub(r"\s+", "", lowered)
    return [
        term
        for term in keywords
        if isinstance(term, str)
        and term.strip()
        and (
            re.sub(r"\s+", "", term.lower()) in compact
            if re.search(r"[가-힣]", term)
            else re.search(r"(?<![a-z0-9])" + re.escape(term.lower()) + r"(?![a-z0-9])", lowered)
        )
    ]
