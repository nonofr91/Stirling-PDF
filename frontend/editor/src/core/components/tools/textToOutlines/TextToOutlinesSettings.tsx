import { Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";

const TextToOutlinesSettings = () => {
  const { t } = useTranslation();

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {t(
          "textToOutlines.help",
          "Converts all text in the document into vector outlines. This is irreversible: text can no longer be selected, searched, copied or edited in the output.",
        )}
      </Text>
      <Text size="sm" c="dimmed">
        {t(
          "textToOutlines.useCase",
          "Useful before sending a document to print when fonts cannot be embedded or licensed, at the cost of larger files and no text accessibility.",
        )}
      </Text>
    </Stack>
  );
};

export default TextToOutlinesSettings;
