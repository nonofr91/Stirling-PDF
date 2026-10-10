import { NumberInput, Select, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  PREFLIGHT_FIXUPS,
  type FixupParamSpec,
  type FixupParams,
} from "@app/data/preflightCatalog";

interface FixupParamsEditorProps {
  /** Effective fixup selection — undefined/empty means every applicable fixup runs. */
  fixups: string[] | undefined;
  fixupParams: FixupParams | undefined;
  onChange: (next: FixupParams | undefined) => void;
  disabled?: boolean;
}

const paramLabel = (
  t: (key: string, fallback: string) => string,
  code: string,
  spec: FixupParamSpec,
) =>
  t(`printPreflight.fixupParams.${code}.${spec.key}.label`, spec.key) +
  (spec.default !== undefined ? ` — ${spec.default}` : "");

const paramHelp = (
  t: (key: string, fallback: string) => string,
  code: string,
  spec: FixupParamSpec,
) =>
  t(
    `printPreflight.fixupParams.${code}.${spec.key}.help`,
    spec.default !== undefined
      ? `Leave empty for the backend default (${spec.default}).`
      : "Leave empty for the backend default.",
  );

/**
 * Generic per-fixup parameter editor driven entirely by the catalog's
 * `FixupDescriptor.params` — registering a parameterized fixup gives it a UI
 * with no bespoke component (contract R5). Values live in
 * `fixupParams.<CODE>.<key>`; a cleared field removes the key so the backend
 * default applies.
 */
const FixupParamsEditor = ({
  fixups,
  fixupParams,
  onChange,
  disabled = false,
}: FixupParamsEditorProps) => {
  const { t } = useTranslation();
  const allSelected = !fixups || fixups.length === 0;
  const parameterized = PREFLIGHT_FIXUPS.filter(
    (f) =>
      f.params.length > 0 &&
      (allSelected
        ? fixups?.includes("NONE") !== true
        : fixups.includes(f.code)),
  );

  if (parameterized.length === 0) return null;

  const setParam = (
    code: string,
    key: string,
    value: string | number | undefined,
  ) => {
    const next: FixupParams = { ...(fixupParams ?? {}) };
    const inner = { ...(next[code] ?? {}) };
    if (value === undefined || value === "") delete inner[key];
    else inner[key] = value;
    if (Object.keys(inner).length === 0) delete next[code];
    else next[code] = inner;
    onChange(Object.keys(next).length === 0 ? undefined : next);
  };

  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed">
        {t(
          "printPreflight.fixupParams.help",
          "Correction parameters — leave a field empty to use its default.",
        )}
      </Text>
      {parameterized.map((fixup) => (
        <Stack key={fixup.code} gap={4} pl="sm">
          <Text size="xs" fw={600}>
            {t(`printPreflight.fixups.codes.${fixup.code}`, fixup.code)}
          </Text>
          {fixup.params.map((spec) =>
            spec.kind === "enum" ? (
              <Select
                key={spec.key}
                size="sm"
                label={paramLabel(t, fixup.code, spec)}
                description={paramHelp(t, fixup.code, spec)}
                data={(spec.options ?? []).map((option) => ({
                  value: option,
                  label: t(
                    `printPreflight.fixupParams.${fixup.code}.${spec.key}.options.${option}`,
                    option,
                  ),
                }))}
                value={
                  typeof fixupParams?.[fixup.code]?.[spec.key] === "string"
                    ? (fixupParams[fixup.code][spec.key] as string)
                    : null
                }
                onChange={(value) =>
                  setParam(fixup.code, spec.key, value ?? undefined)
                }
                clearable
                disabled={disabled}
                comboboxProps={{ withinPortal: true }}
              />
            ) : (
              <NumberInput
                key={spec.key}
                size="sm"
                label={paramLabel(t, fixup.code, spec)}
                description={paramHelp(t, fixup.code, spec)}
                min={spec.minExclusive ? undefined : spec.min}
                max={spec.max}
                decimalScale={3}
                value={
                  typeof fixupParams?.[fixup.code]?.[spec.key] === "number"
                    ? (fixupParams[fixup.code][spec.key] as number)
                    : undefined
                }
                onChange={(value) =>
                  setParam(
                    fixup.code,
                    spec.key,
                    value === "" || value === undefined
                      ? undefined
                      : Number(value),
                  )
                }
                disabled={disabled}
              />
            ),
          )}
        </Stack>
      ))}
    </Stack>
  );
};

export default FixupParamsEditor;
