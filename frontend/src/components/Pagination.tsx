const PAGE_SIZES = [20, 50, 100] as const;

interface PaginationProps {
  page: number;
  totalPages: number;
  totalElements: number;
  size: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
}

export function Pagination({ page, totalPages, totalElements, size, onPageChange, onSizeChange }: PaginationProps) {
  return (
    <div className="pagination">
      <span className="pagination-total">Всего: {totalElements}</span>
      <div className="pagination-pages">
        <button type="button" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
          ←
        </button>
        <span>
          Стр. {totalPages === 0 ? 0 : page + 1} из {totalPages}
        </span>
        <button type="button" disabled={page + 1 >= totalPages} onClick={() => onPageChange(page + 1)}>
          →
        </button>
      </div>
      <select value={size} onChange={(event) => onSizeChange(Number.parseInt(event.target.value, 10))}>
        {PAGE_SIZES.map((option) => (
          <option key={option} value={option}>
            {option} на странице
          </option>
        ))}
      </select>
    </div>
  );
}
