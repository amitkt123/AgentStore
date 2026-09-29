from __future__ import annotations

from collections.abc import Mapping
from typing import Any, TypeVar, cast

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

T = TypeVar("T", bound="Llm")


@_attrs_define
class Llm:
    """
    Attributes:
        mode (str | Unset):
        models (list[str] | Unset):
        tokens_remaining (int | Unset):
    """

    mode: str | Unset = UNSET
    models: list[str] | Unset = UNSET
    tokens_remaining: int | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        mode = self.mode

        models: list[str] | Unset = UNSET
        if not isinstance(self.models, Unset):
            models = self.models

        tokens_remaining = self.tokens_remaining

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if mode is not UNSET:
            field_dict["mode"] = mode
        if models is not UNSET:
            field_dict["models"] = models
        if tokens_remaining is not UNSET:
            field_dict["tokensRemaining"] = tokens_remaining

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        d = dict(src_dict)
        mode = d.pop("mode", UNSET)

        models = cast(list[str], d.pop("models", UNSET))

        tokens_remaining = d.pop("tokensRemaining", UNSET)

        llm = cls(
            mode=mode,
            models=models,
            tokens_remaining=tokens_remaining,
        )

        llm.additional_properties = d
        return llm

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
