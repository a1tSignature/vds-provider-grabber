import type { DictionariesDto } from "../api/types";
import { useDictionaries } from "./DictionariesContext";

interface DictionarySelectProps {
  dictionary: keyof DictionariesDto;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}

export function DictionarySelect({ dictionary, value, onChange, placeholder }: DictionarySelectProps) {
  const dictionaries = useDictionaries();
  const options = dictionaries[dictionary];
  return (
    <select value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="">{placeholder}</option>
      {options.map((option) => (
        <option key={option} value={option}>
          {option}
        </option>
      ))}
    </select>
  );
}
