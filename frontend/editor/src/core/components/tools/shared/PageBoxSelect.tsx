import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { PAGE_BOXES, PageBox } from "@app/constants/pageBoxConstants";

interface PageBoxSelectProps {
  value: PageBox;
  onChange: (value: PageBox) => void;
  label?: string;
  disabled?: boolean;
}

const isPageBox = (value: string): value is PageBox =>
  (PAGE_BOXES as readonly string[]).includes(value);

const PageBoxSelect = ({
  value,
  onChange,
  label,
  disabled = false,
}: PageBoxSelectProps) => {
  const { t } = useTranslation();

  return (
    <Select
      label={label ?? t("pageBox.label", "Page box")}
      data={PAGE_BOXES.map((box) => ({
        value: box,
        label: t(`pageBox.options.${box}`, box),
      }))}
      value={value}
      onChange={(v) => {
        if (v && isPageBox(v)) onChange(v);
      }}
      disabled={disabled}
      allowDeselect={false}
    />
  );
};

export default PageBoxSelect;
