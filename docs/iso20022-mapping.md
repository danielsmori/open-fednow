# ISO 20022 Message Mapping

FedNow uses the ISO 20022 international messaging standard, the same standard used by Brazil's PIX instant payment system and most major real-time payment networks globally.

## Supported Message Types

| Message Type | Description | Direction |
|---|---|---|
| `pacs.008.001.08` | FI-to-FI Customer Credit Transfer | Inbound and Outbound |
| `pacs.002.001.10` | Payment Status Report | Inbound (from FedNow) and Outbound (to FedNow) |
| `pacs.004.001.09` | Payment Return | Outbound (to FedNow, saga compensation path) |

## pacs.008 Key Fields

| ISO 20022 Field | FedNow Usage | Notes |
|-----------------|-------------|-------|
| `MsgId` | Required | Unique per message |
| `NbOfTxs` | Always `1` | FedNow processes one transaction per message |
| `EndToEndId` | Required | Assigned by originator, carried through |
| `TxId` | Required | Assigned by instructing agent |
| `IntrBkSttlmAmt` | Required | USD only for FedNow |
| `DbtrAgt/FinInstnId/ClrSysMmbId` | ABA routing number | 9-digit ABA |
| `CdtrAgt/FinInstnId/ClrSysMmbId` | ABA routing number | 9-digit ABA |

## ISO 20022 Reason Codes (pacs.002 — rejection)

| Code | Meaning |
|------|---------|
| `AC01` | Incorrect account number |
| `AC04` | Closed account number |
| `AM04` | Insufficient funds |
| `NARR` | Narrative reason (see additional information) |
| `FF01` | Invalid file format |

## ISO 20022 Return Reason Codes (pacs.004)

| Code | Meaning |
|------|---------|
| `AC04` | Closed account — account was closed after original transfer was accepted |
| `AM04` | Insufficient funds — core rejected on final posting after FedNow confirmation |
| `FOCR` | Following cancellation request |
| `DUPL` | Duplicate payment detected |
| `NARR` | Narrative reason — see additional information field |

## Vendor Mapping Notes

Vendor-specific rejection codes must be mapped to ISO 20022 reason codes by each adapter. Mapping tables for each vendor are maintained in the respective adapter classes.
