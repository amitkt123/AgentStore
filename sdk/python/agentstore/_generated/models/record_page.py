from __future__ import annotations

from collections.abc import Mapping
from typing import TYPE_CHECKING, Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

if TYPE_CHECKING:
    from ..models.record_page_records_item import RecordPageRecordsItem


T = TypeVar("T", bound="RecordPage")


@_attrs_define
class RecordPage:
    """
    Attributes:
        records (list[RecordPageRecordsItem] | Unset):
        next_cursor (str | Unset):
    """

    records: list[RecordPageRecordsItem] | Unset = UNSET
    next_cursor: str | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        records: list[dict[str, Any]] | Unset = UNSET
        if not isinstance(self.records, Unset):
            records = []
            for records_item_data in self.records:
                records_item = records_item_data.to_dict()
                records.append(records_item)

        next_cursor = self.next_cursor

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if records is not UNSET:
            field_dict["records"] = records
        if next_cursor is not UNSET:
            field_dict["nextCursor"] = next_cursor

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        from ..models.record_page_records_item import (
            RecordPageRecordsItem,
        )

        d = dict(src_dict)
        _records = d.pop("records", UNSET)
        records: list[RecordPageRecordsItem] | Unset = UNSET
        if _records is not UNSET:
            records = []
            for records_item_data in _records:
                records_item = RecordPageRecordsItem.from_dict(records_item_data)

                records.append(records_item)

        next_cursor = d.pop("nextCursor", UNSET)

        record_page = cls(
            records=records,
            next_cursor=next_cursor,
        )

        record_page.additional_properties = d
        return record_page

    @property
    def additional_keys(self) -> list[str]:
        return list(self.additional_properties.keys())

    def __getitem__(self, key: str) -> Any:
        return self.additional_properties[key]

    def __setitem__(self, key: str, value: Any) -> None:
        self.additional_properties[key] = value

    def __delitem__(self, key: str) -> None:
        del self.additional_properties[key]

    def __contains__(self, key: str) -> bool:
        return key in self.additional_properties
