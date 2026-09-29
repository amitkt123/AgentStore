from __future__ import annotations

from collections.abc import Mapping
from typing import TYPE_CHECKING, Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

if TYPE_CHECKING:
    from ..models.write_records_request_records_item import (
        WriteRecordsRequestRecordsItem,
    )


T = TypeVar("T", bound="WriteRecordsRequest")


@_attrs_define
class WriteRecordsRequest:
    """
    Attributes:
        records (list[WriteRecordsRequestRecordsItem]):
    """

    records: list[WriteRecordsRequestRecordsItem]
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        records = []
        for records_item_data in self.records:
            records_item = records_item_data.to_dict()
            records.append(records_item)

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update(
            {
                "records": records,
            }
        )

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        from ..models.write_records_request_records_item import (
            WriteRecordsRequestRecordsItem,
        )

        d = dict(src_dict)
        records = []
        _records = d.pop("records")
        for records_item_data in _records:
            records_item = WriteRecordsRequestRecordsItem.from_dict(records_item_data)

            records.append(records_item)

        write_records_request = cls(
            records=records,
        )

        write_records_request.additional_properties = d
        return write_records_request

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
